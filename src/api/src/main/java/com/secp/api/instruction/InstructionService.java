package com.secp.api.instruction;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.idempotency.IdempotencyResult;
import com.secp.api.idempotency.IdempotencyService;
import com.secp.api.idempotency.RequestHashing;
import com.secp.api.idempotency.ResponseJson;
import com.secp.api.infra.RequestIdFilter;
import com.secp.api.infra.tx.TransactionalExecutor;
import com.secp.api.instruction.dto.*;
import com.secp.api.task.TaskRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InstructionService {

  private final TransactionalExecutor tx;
  private final JdbcTemplate jdbc;
  private final InstructionRepository instructionRepository;
  private final TaskRepository taskRepository;
  private final IdempotencyService idempotencyService;
  private final ResponseJson responseJson;
  private final RequestHashing requestHashing;

  public CreateInstructionResponse create(AuthPrincipal principal, CreateInstructionRequest req, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      String refType = normalizeRefType(req.refType());
      UUID groupId;

      if ("project".equals(refType)) {
        groupId = instructionRepository.findGroupIdByProjectId(req.refId())
            .orElseThrow(InstructionNotFoundException::new);
      } else {
        var caseRow = instructionRepository.findCase(req.refId()).orElseThrow(InstructionNotFoundException::new);
        groupId = (UUID) caseRow.get("group_id");
      }

      Boolean allowed = jdbc.queryForObject("select app_can_write_group(?)", Boolean.class, groupId);
      if (!Boolean.TRUE.equals(allowed)) {
        throw new IllegalArgumentException("FORBIDDEN");
      }

      UUID instructionId = UUID.randomUUID();
      instructionRepository.insertInstruction(instructionId, groupId, refType, req.refId(), req.title(), principal.userId());

      for (CreateInstructionItemRequest item : req.items()) {
        UUID itemId = UUID.randomUUID();
        instructionRepository.insertInstructionItem(itemId, instructionId, groupId, item.title(), item.dueAt(), principal.userId());
      }

      writeAudit(httpReq, principal.userId(), groupId,
          "Instruction.Created",
          "instruction",
          instructionId,
          responseJson.toJson(Map.of(
              "instructionId", instructionId,
              "refType", refType,
              "refId", req.refId(),
              "itemCount", req.items().size()
          ))
      );

      writeOutbox(groupId, null, null, principal.userId(),
          "Instruction.Created",
          "Instruction.Created:instruction:" + instructionId + ":v1",
          responseJson.toJson(Map.of("instructionId", instructionId))
      );

      return new CreateInstructionResponse(instructionId);
    });
  }

  public record JsonResponse(int statusCode, String bodyJson) {
  }

  public JsonResponse issue(AuthPrincipal principal,
                           UUID instructionId,
                           IssueInstructionRequest req,
                           HttpServletRequest httpReq,
                           String idemKey) {
    if (idemKey == null || idemKey.isBlank()) {
      throw new IllegalArgumentException("IDEMPOTENCY_KEY_REQUIRED");
    }

    String method = httpReq.getMethod();
    String path = httpReq.getRequestURI();
    String scope = method + " " + path;
    String requestHash = requestHashing.hash(method, path, req);

    return tx.execute(principal, () -> {
      IdempotencyResult pre = idempotencyService.preCheck(principal.userId(), scope, idemKey, requestHash);
      if (pre.replay()) {
        return new JsonResponse(pre.statusCode(), pre.responseBodyJson());
      }

      InstructionRepository.InstructionRow row = instructionRepository.findInstructionForUpdate(instructionId)
          .orElseThrow(InstructionNotFoundException::new);

      if (!"DRAFT".equals(row.status())) {
        throw new InstructionConflictException("ALREADY_ISSUED");
      }

      UUID projectId;
      UUID caseId = null;

      if ("project".equals(row.refType())) {
        projectId = row.refId();
        if (req != null && req.targetCaseId() != null) {
          instructionRepository.findCaseIdInProject(req.targetCaseId(), projectId)
              .orElseThrow(() -> new UnprocessableEntityException("CASE_NOT_IN_PROJECT"));
          caseId = req.targetCaseId();
        }
      } else {
        caseId = row.refId();
        var caseRow = instructionRepository.findCase(caseId).orElseThrow(InstructionNotFoundException::new);
        projectId = (UUID) caseRow.get("project_id");
      }

      int newVersion = instructionRepository.issueInstruction(instructionId, principal.userId());

      List<InstructionRepository.InstructionItemRow> items = instructionRepository.listItems(instructionId);
      List<UUID> taskIds = items.stream().map(it -> UUID.randomUUID()).toList();

      for (int i = 0; i < items.size(); i++) {
        InstructionRepository.InstructionItemRow item = items.get(i);
        UUID taskId = taskIds.get(i);
        taskRepository.insertTask(
            taskId,
            row.groupId(),
            projectId,
            caseId,
            item.title(),
            principal.userId(),
            principal.userId(),
            item.id(),
            item.dueAt(),
            "NORMAL"
        );

        writeOutbox(row.groupId(), projectId, caseId, principal.userId(),
            "Task.Assigned",
            "Task.Assigned:task:" + taskId + ":v1",
            responseJson.toJson(Map.of(
                "taskId", taskId,
                "instructionId", instructionId,
                "instructionItemId", item.id()
            ))
        );
      }

      writeAudit(httpReq, principal.userId(), row.groupId(),
          "Instruction.Issued",
          "instruction",
          instructionId,
          responseJson.toJson(Map.of(
              "instructionId", instructionId,
              "version", newVersion,
              "taskCount", taskIds.size()
          ))
      );

      writeOutbox(row.groupId(), projectId, caseId, principal.userId(),
          "Instruction.Issued",
          "Instruction.Issued:instruction:" + instructionId + ":v" + newVersion,
          responseJson.toJson(Map.of(
              "instructionId", instructionId,
              "version", newVersion,
              "taskIds", taskIds
          ))
      );

      String body = responseJson.toJson(new IssueInstructionResponse(instructionId, newVersion, taskIds));
      idempotencyService.complete(principal.userId(), scope, idemKey, 200, body);
      return new JsonResponse(200, body);
    });
  }

  public UpdateInstructionItemStatusResponse updateItemStatus(AuthPrincipal principal,
                                                             UUID itemId,
                                                             UpdateInstructionItemStatusRequest req,
                                                             HttpServletRequest httpReq) {
    String normalized = normalizeItemStatus(req.status());

    return tx.execute(principal, () -> {
      var current = instructionRepository.findItem(itemId).orElseThrow(InstructionNotFoundException::new);

      if (normalized.equals(current.status())) {
        // No-op: do not write audit/outbox.
        return new UpdateInstructionItemStatusResponse(itemId, current.status());
      }

      var changed = instructionRepository.changeItemStatus(itemId, current.status(), normalized, principal.userId())
          .orElseThrow(InstructionNotFoundException::new);

        writeAudit(httpReq, principal.userId(), changed.groupId(),
          "InstructionItem.StatusUpdated",
          "instruction_item",
          itemId,
          responseJson.toJson(Map.of(
              "instructionItemId", itemId,
            "instructionId", changed.instructionId(),
            "fromStatus", changed.fromStatus(),
            "toStatus", changed.toStatus(),
            "statusVersion", changed.statusVersion()
          ))
      );

        writeOutbox(changed.groupId(), null, null, principal.userId(),
          "InstructionItem.StatusUpdated",
          "InstructionItem.StatusUpdated:instruction_item:" + itemId + ":v" + changed.statusVersion(),
          responseJson.toJson(Map.of(
              "instructionItemId", itemId,
            "instructionId", changed.instructionId(),
            "fromStatus", changed.fromStatus(),
            "toStatus", changed.toStatus(),
            "statusVersion", changed.statusVersion(),
            "changedByUserId", principal.userId()
          ))
      );

        writeOutbox(changed.groupId(), null, null, principal.userId(),
          "InstructionItem.StatusChanged",
          "InstructionItem.StatusChanged:instruction_item:" + itemId + ":v" + changed.statusVersion(),
          responseJson.toJson(Map.of(
            "instructionItemId", itemId,
            "instructionId", changed.instructionId(),
            "fromStatus", changed.fromStatus(),
            "toStatus", changed.toStatus(),
            "statusVersion", changed.statusVersion(),
            "changedByUserId", principal.userId()
          ))
        );

        return new UpdateInstructionItemStatusResponse(itemId, changed.toStatus());
    });
  }

  private String normalizeRefType(String refType) {
    if (refType == null) throw new IllegalArgumentException("BAD_REQUEST");
    String v = refType.trim().toLowerCase();
    if (!"project".equals(v) && !"case".equals(v)) {
      throw new IllegalArgumentException("BAD_REQUEST");
    }
    return v;
  }

  private String normalizeItemStatus(String status) {
    if (status == null) throw new IllegalArgumentException("BAD_REQUEST");
    String v = status.trim().toUpperCase();
    if (!"DONE".equals(v) && !"OPEN".equals(v)) {
      throw new IllegalArgumentException("BAD_REQUEST");
    }
    return v;
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
