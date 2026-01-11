package com.secp.api.evidence;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.evidence.dto.CreateEvidenceRequest;
import com.secp.api.evidence.dto.CreateEvidenceResponse;
import com.secp.api.idempotency.ResponseJson;
import com.secp.api.infra.RequestIdFilter;
import com.secp.api.infra.tx.TransactionalExecutor;
import com.secp.api.instruction.InstructionNotFoundException;
import com.secp.api.instruction.UnprocessableEntityException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EvidenceService {

  private final TransactionalExecutor tx;
  private final JdbcTemplate jdbc;
  private final EvidenceRepository evidenceRepository;
  private final ResponseJson responseJson;

  public CreateEvidenceResponse create(AuthPrincipal principal, CreateEvidenceRequest req, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      var project = evidenceRepository.findProject(req.projectId()).orElseThrow(InstructionNotFoundException::new);
      UUID groupId = (UUID) project.get("group_id");

      Boolean allowed = jdbc.queryForObject("select app_can_write_group(?)", Boolean.class, groupId);
      if (!Boolean.TRUE.equals(allowed)) {
        throw new IllegalArgumentException("FORBIDDEN");
      }

      if (req.caseId() != null && !evidenceRepository.caseBelongsToProject(req.caseId(), req.projectId())) {
        throw new UnprocessableEntityException("CASE_NOT_IN_PROJECT");
      }

      if (req.fileId() != null) {
        var file = evidenceRepository.findFile(req.fileId()).orElseThrow(() -> new UnprocessableEntityException("FILE_NOT_IN_PROJECT"));
        if (!req.projectId().equals(file.projectId())) {
          throw new UnprocessableEntityException("FILE_NOT_IN_PROJECT");
        }
        if (req.caseId() != null && file.caseId() != null && !req.caseId().equals(file.caseId())) {
          throw new UnprocessableEntityException("FILE_NOT_IN_CASE");
        }
        if (!"READY".equalsIgnoreCase(file.status())) {
          throw new UnprocessableEntityException("FILE_NOT_READY");
        }
      }

      UUID evidenceId = UUID.randomUUID();
      evidenceRepository.insertEvidence(evidenceId, groupId, req.projectId(), req.caseId(), req.title(), req.fileId(), principal.userId());

      writeAudit(httpReq, principal.userId(), groupId,
          "Evidence.Created",
          "evidence",
          evidenceId,
          responseJson.toJson(Map.of(
              "evidenceId", evidenceId,
              "projectId", req.projectId(),
              "caseId", req.caseId(),
              "fileId", req.fileId()
          ))
      );

      writeOutbox(groupId, req.projectId(), req.caseId(), principal.userId(),
          "Evidence.Created",
          "Evidence.Created:evidence:" + evidenceId + ":v1",
          responseJson.toJson(Map.of(
              "evidenceId", evidenceId,
              "projectId", req.projectId(),
              "caseId", req.caseId()
          ))
      );

      return new CreateEvidenceResponse(evidenceId);
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

  private void writeOutbox(UUID groupId,
                           UUID projectId,
                           UUID caseId,
                           UUID actorUserId,
                           String eventType,
                           String dedupeKey,
                           String payloadJson) {
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
