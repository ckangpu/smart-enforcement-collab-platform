package com.secp.api.task;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.idempotency.ResponseJson;
import com.secp.api.infra.RequestIdFilter;
import com.secp.api.infra.tx.TransactionalExecutor;
import com.secp.api.instruction.UnprocessableEntityException;
import com.secp.api.task.dto.CreateTaskPaymentRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskPaymentService {

  private final JdbcTemplate jdbc;
  private final TransactionalExecutor tx;
  private final TaskRepository taskRepository;
  private final ResponseJson responseJson;

  public record JsonResponse(int statusCode, String bodyJson) {
  }

  public JsonResponse createPaymentForTask(AuthPrincipal principal,
                                          UUID taskId,
                                          CreateTaskPaymentRequest req,
                                          HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      TaskRepository.TaskCore task = taskRepository.findTaskCore(taskId)
          .orElseThrow(TaskNotFoundException::new);

      if (task.caseId() == null) {
        throw new UnprocessableEntityException("TASK_HAS_NO_CASE");
      }

      UUID paymentId = UUID.randomUUID();
      jdbc.update(
          """
          insert into payment(
            id, group_id, project_id, case_id,
            amount, paid_at, pay_channel, payer_name, bank_last4,
            client_note, internal_note, is_client_visible,
            created_by
          ) values (?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,
          paymentId,
          task.groupId(),
          task.projectId(),
          task.caseId(),
          req.amount(),
          req.paidAt(),
          req.payChannel(),
          req.payerName(),
          req.bankLast4(),
          req.clientNote(),
          req.internalNote(),
          req.isClientVisible() != null ? req.isClientVisible() : true,
          principal.userId()
      );

      writeAudit(httpReq, principal.userId(), task.groupId(),
          "Payment.Created",
          "payment",
          paymentId,
          responseJson.toJson(Map.of(
              "paymentId", paymentId,
              "taskId", taskId
          ))
      );

      writeOutbox(task.groupId(), task.projectId(), task.caseId(), principal.userId(),
          "Payment.Created",
          "Payment.Created:payment:" + paymentId + ":v1",
          responseJson.toJson(Map.of(
              "paymentId", paymentId,
              "taskId", taskId
          ))
      );

      String body = responseJson.toJson(Map.of("paymentId", paymentId));
      return new JsonResponse(200, body);
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
