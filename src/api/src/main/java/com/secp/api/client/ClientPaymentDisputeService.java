package com.secp.api.client;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.client.dto.CreatePaymentDisputeRequest;
import com.secp.api.idempotency.IdempotencyResult;
import com.secp.api.idempotency.IdempotencyService;
import com.secp.api.idempotency.RequestHashing;
import com.secp.api.idempotency.ResponseJson;
import com.secp.api.infra.RequestIdFilter;
import com.secp.api.infra.tx.TransactionalExecutor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClientPaymentDisputeService {

  private final TransactionalExecutor tx;
  private final JdbcTemplate jdbc;
  private final IdempotencyService idempotencyService;
  private final RequestHashing requestHashing;
  private final ResponseJson responseJson;

  @Value("${secp.client.payment-dispute.sla-hours:48}")
  private long slaHours;

  public record JsonResponse(int statusCode, String bodyJson) {
  }

  public JsonResponse createPaymentDispute(AuthPrincipal client,
                                          UUID paymentId,
                                          CreatePaymentDisputeRequest req,
                                          HttpServletRequest httpReq,
                                          String idemKey) {
    String method = httpReq.getMethod();
    String path = httpReq.getRequestURI();
    String scope = method + " " + path;
    String requestHash = requestHashing.hash(method, path, req);

    return tx.execute(client, () -> {
      IdempotencyResult pre = idempotencyService.preCheck(client.userId(), scope, idemKey, requestHash);
      if (pre.replay()) {
        return new JsonResponse(pre.statusCode(), pre.responseBodyJson());
      }

      Map<String, Object> row = findClientPaymentRow(client, paymentId);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      UUID caseId = (UUID) row.get("case_id");
      UUID customerId = (UUID) row.get("customer_id");

      UUID disputeId = UUID.randomUUID();
      OffsetDateTime slaDueAt = OffsetDateTime.now().plusHours(Math.max(1, slaHours));

      jdbc.update("""
          insert into reconcile_complaint(
            id, customer_id, project_id, payment_id,
            status, title, message,
            type, sla_due_at
          ) values (?,?,?,?,?,?,?,?,?)
          """,
          disputeId, customerId, projectId, paymentId,
          "OPEN", req.title(), req.message(),
          "payment_dispute", slaDueAt
      );

      writeAudit(httpReq, client.userId(), groupId, "Client.PaymentDispute.Created", "reconcile_complaint", disputeId);
      writeOutbox(groupId, projectId, caseId, client.userId(),
          "Client.PaymentDispute.Created",
          "Client.PaymentDispute.Created:reconcile_complaint:" + disputeId + ":v1",
          responseJson.toJson(Map.of(
              "disputeId", disputeId,
              "paymentId", paymentId
          ))
      );

      String body = responseJson.toJson(Map.of(
          "disputeId", disputeId,
          "status", "OPEN"
      ));
      idempotencyService.complete(client.userId(), scope, idemKey, 201, body);
      return new JsonResponse(201, body);
    });
  }

  private Map<String, Object> findClientPaymentRow(AuthPrincipal client, UUID paymentId) {
    var rows = jdbc.queryForList(
        """
            select p.group_id, p.project_id, p.case_id, u.customer_id
            from payment p
            join project pr on pr.id = p.project_id
            join app_user u on u.id = ?
            where u.user_type = 'client'
              and u.customer_id is not null
              and pr.customer_id = u.customer_id
              and p.id = ?
              and p.is_client_visible = true
            """,
        client.userId(),
        paymentId
    );
    if (rows.isEmpty()) {
      throw new ClientNotFoundException();
    }
    return rows.getFirst();
  }

  private void writeAudit(HttpServletRequest req, UUID actorUserId, UUID groupId, String action, String objectType, UUID objectId) {
    String rid = (String) req.getAttribute(RequestIdFilter.REQ_ID_ATTR);
    jdbc.update("""
        insert into audit_log(group_id, actor_user_id, action, object_type, object_id, request_id, ip, user_agent, summary)
        values (?,?,?,?,?,?,?,?, '{}'::jsonb)
        """,
        groupId,
        actorUserId,
        action, objectType, objectId,
        rid,
        req.getRemoteAddr(),
        req.getHeader("User-Agent")
    );
  }

  private void writeOutbox(UUID groupId, UUID projectId, UUID caseId, UUID actorUserId,
                           String eventType, String dedupeKey, String payloadJson) {
    jdbc.update("""
        insert into event_outbox(event_type, dedupe_key, group_id, project_id, case_id, actor_user_id, payload)
        values (?,?,?,?,?,?, ?::jsonb)
        """,
        eventType, dedupeKey, groupId, projectId, caseId,
        actorUserId,
        payloadJson
    );
  }
}
