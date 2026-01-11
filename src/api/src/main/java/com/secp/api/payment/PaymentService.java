package com.secp.api.payment;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.idempotency.IdempotencyResult;
import com.secp.api.idempotency.IdempotencyService;
import com.secp.api.idempotency.RequestHashing;
import com.secp.api.idempotency.ResponseJson;
import com.secp.api.infra.RequestIdFilter;
import com.secp.api.infra.tx.TransactionalExecutor;
import com.secp.api.payment.dto.CreatePaymentRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

  private final JdbcTemplate jdbc;
  private final TransactionalExecutor tx;
  private final IdempotencyService idempotencyService;
  private final ResponseJson responseJson;
  private final RequestHashing requestHashing;

  public record JsonResponse(int statusCode, String bodyJson) {
  }

  public JsonResponse createPayment(AuthPrincipal principal, CreatePaymentRequest req, HttpServletRequest httpReq, String idemKey) {
    String method = httpReq.getMethod();
    String path = httpReq.getRequestURI();
    String scope = method + " " + path;
    String requestHash = requestHashing.hash(method, path, req);

    return tx.execute(principal, () -> {
      IdempotencyResult pre = idempotencyService.preCheck(principal.userId(), scope, idemKey, requestHash);
      if (pre.replay()) {
        return new JsonResponse(pre.statusCode(), pre.responseBodyJson());
      }

      UUID groupId = jdbc.queryForObject(
          "select group_id from \"case\" where id = ?",
          (rs, rowNum) -> UUID.fromString(rs.getString(1)),
          req.caseId()
      );

      UUID paymentId = UUID.randomUUID();
      jdbc.update("""
          insert into payment(
            id, group_id, project_id, case_id,
            amount, paid_at, pay_channel, payer_name, bank_last4,
            client_note, internal_note, is_client_visible,
            created_by
          ) values (?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,
          paymentId, groupId, req.projectId(), req.caseId(),
          req.amount(), req.paidAt(), req.payChannel(), req.payerName(), req.bankLast4(),
          req.clientNote(), req.internalNote(), req.isClientVisible() != null ? req.isClientVisible() : true,
          principal.userId()
      );

      writeAudit(httpReq, principal.userId(), groupId, "Payment.Created", "payment", paymentId);
      writeOutbox(groupId, req.projectId(), req.caseId(), principal.userId(),
          "Payment.Created",
          "Payment.Created:payment:" + paymentId + ":v1",
          "{\"paymentId\":\"" + paymentId + "\"}"
      );

      String body = responseJson.toJson(Map.of("paymentId", paymentId));
      idempotencyService.complete(principal.userId(), scope, idemKey, 200, body);
      return new JsonResponse(200, body);
    });
  }

  public JsonResponse correctPayment(AuthPrincipal principal, UUID oldPaymentId, String reason, HttpServletRequest httpReq, String idemKey) {
    String method = httpReq.getMethod();
    String path = httpReq.getRequestURI();
    String scope = method + " " + path;
    String requestHash = requestHashing.hash(method, path, Map.of("reason", reason));

    return tx.execute(principal, () -> {
      IdempotencyResult pre = idempotencyService.preCheck(principal.userId(), scope, idemKey, requestHash);
      if (pre.replay()) {
        return new JsonResponse(pre.statusCode(), pre.responseBodyJson());
      }

      var row = jdbc.queryForMap(
          "select group_id, project_id, case_id, amount, paid_at, pay_channel, payer_name, bank_last4 from payment where id = ?",
          oldPaymentId
      );
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      UUID caseId = (UUID) row.get("case_id");
      BigDecimal amount = (BigDecimal) row.get("amount");
      OffsetDateTime paidAt = (OffsetDateTime) row.get("paid_at");
      String payChannel = (String) row.get("pay_channel");
      String payerName = (String) row.get("payer_name");
      String bankLast4 = (String) row.get("bank_last4");

      UUID newId = UUID.randomUUID();
      jdbc.update("""
          insert into payment(
            id, group_id, project_id, case_id,
            amount, paid_at, pay_channel, payer_name, bank_last4,
            corrected_from_payment_id, correction_reason,
            created_by
          ) values (?,?,?,?,?,?,?,?,?,?,?,?,?)
          """,
          newId, groupId, projectId, caseId,
          amount, paidAt, payChannel, payerName, bankLast4,
          oldPaymentId, reason,
          principal.userId()
      );

      writeAudit(httpReq, principal.userId(), groupId, "Payment.Corrected", "payment", newId);
      writeOutbox(groupId, projectId, caseId, principal.userId(),
          "Payment.Corrected",
          "Payment.Corrected:payment:" + newId + ":v1",
          "{\"oldPaymentId\":\"" + oldPaymentId + "\",\"newPaymentId\":\"" + newId + "\"}"
      );

      String body = responseJson.toJson(Map.of("newPaymentId", newId));
      idempotencyService.complete(principal.userId(), scope, idemKey, 200, body);
      return new JsonResponse(200, body);
    });
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
