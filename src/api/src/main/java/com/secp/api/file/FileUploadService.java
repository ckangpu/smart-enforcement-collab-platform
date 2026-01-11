package com.secp.api.file;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.file.dto.UploadCompleteRequest;
import com.secp.api.file.dto.UploadCompleteResponse;
import com.secp.api.file.dto.UploadInitRequest;
import com.secp.api.file.dto.UploadInitResponse;
import com.secp.api.idempotency.ResponseJson;
import com.secp.api.infra.RequestIdFilter;
import com.secp.api.infra.s3.S3Storage;
import com.secp.api.infra.tx.TransactionalExecutor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileUploadService {

  private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(10);

  private final TransactionalExecutor tx;
  private final JdbcTemplate jdbc;
  private final S3Storage s3;
  private final ResponseJson responseJson;

  public UploadInitResponse uploadInit(AuthPrincipal principal, UploadInitRequest req) {
    // Internal-only: client/external are blocked here by InternalApiGuardFilter already.
    return tx.execute(principal, () -> {
      var row = jdbc.queryForMap("select group_id, project_id from \"case\" where id=?", req.caseId());
      UUID groupId = (UUID) row.get("group_id");

      Boolean allowed = jdbc.queryForObject("select app_can_write_group(?)", Boolean.class, groupId);
      if (!Boolean.TRUE.equals(allowed)) {
        throw new IllegalArgumentException("FORBIDDEN");
      }

      UUID fileId = UUID.randomUUID();
      String safeName = req.filename().replaceAll("[^a-zA-Z0-9._-]", "_");
      String key = "raw/" + fileId + "/" + safeName;

      var presigned = s3.presignPut(key, req.contentType(), UPLOAD_URL_TTL);
      return new UploadInitResponse(
          fileId,
          key,
          presigned.url(),
          OffsetDateTime.now().plus(UPLOAD_URL_TTL)
      );
    });
  }

  public UploadCompleteResponse uploadComplete(AuthPrincipal principal, UploadCompleteRequest req, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      var caseRow = jdbc.queryForMap("select group_id, project_id from \"case\" where id=?", req.caseId());
      UUID groupId = (UUID) caseRow.get("group_id");
      UUID projectId = (UUID) caseRow.get("project_id");

      Boolean allowed = jdbc.queryForObject("select app_can_write_group(?)", Boolean.class, groupId);
      if (!Boolean.TRUE.equals(allowed)) {
        throw new IllegalArgumentException("FORBIDDEN");
      }

      // Ensure object exists
      var head = s3.head(req.s3KeyRaw());
      long size = head.contentLength() == null ? 0L : head.contentLength();
      String etag = head.eTag();

      // Ensure file_store exists at least as INIT (for retry safety)
      jdbc.update(
          """
          insert into file_store(
            id, group_id, project_id, case_id,
            filename, content_type, size_bytes, sha256,
            s3_key_raw,
            created_by,
            status,
            etag
          ) values (?,?,?,?,?,?,?,?,?,?,?,?,?)
          on conflict (id) do nothing
          """,
          req.fileId(),
          groupId,
          projectId,
          req.caseId(),
          req.filename(),
          req.contentType(),
          req.sizeBytes() != null ? req.sizeBytes() : size,
          req.sha256(),
          req.s3KeyRaw(),
          principal.userId(),
          "INIT",
          etag
      );

      // Transition INIT -> READY exactly once
      var transitioned = jdbc.queryForList(
          """
          update file_store
             set filename = ?,
                 content_type = ?,
                 size_bytes = ?,
                 sha256 = ?,
                 s3_key_raw = ?,
                 status = 'READY',
                 etag = ?
           where id = ?
             and group_id = ?
             and project_id = ?
             and case_id = ?
             and status = 'INIT'
          returning id
          """,
          req.filename(),
          req.contentType(),
          req.sizeBytes() != null ? req.sizeBytes() : size,
          req.sha256(),
          req.s3KeyRaw(),
          etag,
          req.fileId(),
          groupId,
          projectId,
          req.caseId()
      );

      boolean didTransition = !transitioned.isEmpty();

      if (!didTransition) {
        // If already READY, treat as idempotent success without duplicate audit/outbox.
        var rows = jdbc.queryForList("select status from file_store where id=?", req.fileId());
        if (rows.isEmpty()) {
          throw new IllegalStateException("FILE_NOT_FOUND");
        }
        String status = String.valueOf(rows.getFirst().get("status"));
        if (!"READY".equals(status)) {
          throw new IllegalStateException("FILE_NOT_READY");
        }
        return new UploadCompleteResponse(req.fileId());
      }

      writeAudit(httpReq, principal.userId(), groupId, "upload_complete", "file_store", req.fileId(),
          responseJson.toJson(Map.of(
              "fileId", req.fileId(),
              "caseId", req.caseId(),
              "projectId", projectId,
              "etag", etag
          ))
      );

      writeOutbox(groupId, projectId, req.caseId(), principal.userId(),
          "File.Uploaded",
          "File.Uploaded:file:" + req.fileId() + ":v1",
          responseJson.toJson(Map.of(
              "fileId", req.fileId(),
              "caseId", req.caseId(),
              "projectId", projectId,
              "s3KeyRaw", req.s3KeyRaw(),
              "contentType", req.contentType(),
              "sizeBytes", req.sizeBytes() != null ? req.sizeBytes() : size,
              "etag", etag
          ))
      );

      return new UploadCompleteResponse(req.fileId());
    });
  }

  private void writeAudit(HttpServletRequest req,
                          UUID actorUserId,
                          UUID groupId,
                          String action,
                          String objectType,
                          UUID objectId,
                          String summaryJson) {
    String rid = (String) req.getAttribute(RequestIdFilter.REQ_ID_ATTR);
    jdbc.update(
        """
      insert into audit_log(group_id, actor_user_id, action, object_type, object_id, request_id, ip, user_agent, summary)
      values (?,?,?,?,?,?,?,?, ?::jsonb)
      """,
        groupId,
        actorUserId,
        action,
        objectType,
        objectId,
        rid,
        req.getRemoteAddr(),
        req.getHeader("User-Agent"),
        summaryJson
    );
  }

  private void writeOutbox(UUID groupId, UUID projectId, UUID caseId, UUID actorUserId,
                           String eventType, String dedupeKey, String payloadJson) {
    jdbc.update(
        """
        insert into event_outbox(event_type, dedupe_key, group_id, project_id, case_id, actor_user_id, payload)
        values (?,?,?,?,?,?, ?::jsonb)
        """,
        eventType,
        dedupeKey,
        groupId,
        projectId,
        caseId,
        actorUserId,
        payloadJson
    );
  }
}
