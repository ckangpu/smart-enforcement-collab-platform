package com.secp.api.it;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.auth.JwtService;
import com.secp.api.infra.RlsSessionJdbc;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientApiIsolationIT extends IntegrationTestBase {

  @Autowired TestRestTemplate rest;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;
  @Autowired JwtService jwtService;
  @Autowired RlsSessionJdbc rlsSessionJdbc;

  @Test
  void testClientPaymentsEffectiveOnly() {
    UUID customerA = UUID.randomUUID();
    UUID customerB = UUID.randomUUID();

    UUID clientA = UUID.randomUUID();
    UUID clientB = UUID.randomUUID();
    UUID internalAdmin = UUID.randomUUID();

    UUID group = UUID.randomUUID();
    UUID projectA = UUID.randomUUID();
    UUID projectB = UUID.randomUUID();
    UUID caseA = UUID.randomUUID();
    UUID caseB = UUID.randomUUID();

    seedBase(customerA, customerB, clientA, clientB, internalAdmin, group, projectA, projectB, caseA, caseB);

    String internalToken = jwtService.sign(new AuthPrincipal(internalAdmin, true, "internal", "internal"));
    HttpHeaders internalHeaders = bearer(internalToken);

    UUID paymentOld = createPayment(internalHeaders, projectA, caseA, new BigDecimal("100.00"), "BANK", "payerA", "1234", true);
    UUID hidden = createPayment(internalHeaders, projectA, caseA, new BigDecimal("66.00"), "BANK", "hiddenPayer", "6666", false);
    assertNotNull(hidden);

    UUID paymentNew = correctPayment(internalHeaders, paymentOld, "fix");

    String clientToken = jwtService.sign(new AuthPrincipal(clientA, false, "clientA", "client"));
    HttpHeaders clientHeaders = bearer(clientToken);

    ResponseEntity<List> payments = rest.exchange(
        "/client/projects/" + projectA + "/payments",
        HttpMethod.GET,
        new HttpEntity<>(clientHeaders),
        List.class
    );
    assertEquals(200, payments.getStatusCode().value());
    assertNotNull(payments.getBody());

    // payment_old must be hidden because a newer correction exists;
    // hidden payment must be hidden because is_client_visible=false.
    assertEquals(1, payments.getBody().size());
    Map<?, ?> pay0 = (Map<?, ?>) payments.getBody().get(0);
    assertEquals(paymentNew.toString(), String.valueOf(pay0.get("paymentId")));
    assertEquals(true, pay0.get("correctedFlag"));

    assertPaymentWhitelist(pay0);
  }

  @Test
  void testClientDisputeE2E() {
    UUID customerA = UUID.randomUUID();

    UUID clientA = UUID.randomUUID();
    UUID internalAdmin = UUID.randomUUID();

    UUID group = UUID.randomUUID();
    UUID projectA = UUID.randomUUID();
    UUID caseA = UUID.randomUUID();

    seedBaseSingleCustomer(customerA, clientA, internalAdmin, group, projectA, caseA);

    String internalToken = jwtService.sign(new AuthPrincipal(internalAdmin, true, "internal", "internal"));
    HttpHeaders internalHeaders = bearer(internalToken);
    UUID paymentOld = createPayment(internalHeaders, projectA, caseA, new BigDecimal("100.00"), "BANK", "payerA", "1234", true);
    UUID paymentNew = correctPayment(internalHeaders, paymentOld, "fix");

    String clientToken = jwtService.sign(new AuthPrincipal(clientA, false, "clientA", "client"));
    HttpHeaders clientHeaders = bearer(clientToken);

    OffsetDateTime before = OffsetDateTime.now();
    Map<String, Object> req = Map.of("title", "对账争议", "message", "请核对金额");

    ResponseEntity<Map> create = rest.exchange(
        "/client/payments/" + paymentNew + "/disputes",
        HttpMethod.POST,
        new HttpEntity<>(req, json(clientHeaders)),
        Map.class
    );
    assertEquals(201, create.getStatusCode().value());
    assertNotNull(create.getBody());
    UUID disputeId = UUID.fromString(String.valueOf(create.getBody().get("disputeId")));
    assertEquals("OPEN", String.valueOf(create.getBody().get("status")));

    // Client can see the created complaint record
    ResponseEntity<List> complaints = rest.exchange(
        "/client/complaints",
        HttpMethod.GET,
        new HttpEntity<>(clientHeaders),
        List.class
    );
    assertEquals(200, complaints.getStatusCode().value());
    assertNotNull(complaints.getBody());
    assertFalse(complaints.getBody().isEmpty());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> complaintList = (List<Map<String, Object>>) (List<?>) complaints.getBody();
    Map<String, Object> found = complaintList.stream()
      .filter(it -> disputeId.toString().equals(String.valueOf(it.get("id"))))
      .findFirst()
      .orElseThrow();
    assertEquals(paymentNew.toString(), String.valueOf(found.get("paymentId")));
    assertEquals(projectA.toString(), String.valueOf(found.get("projectId")));
    assertEquals("OPEN", String.valueOf(found.get("status")));
    assertComplaintWhitelist(found);

    // Verify DB associations + SLA/type without bypassing RLS (admin session)
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      rlsSessionJdbc.applyRlsSession(internalAdmin.toString(), true, "");
      Map<String, Object> row = jdbc.queryForMap(
          "select type, sla_due_at, customer_id, project_id, payment_id from reconcile_complaint where id = ?",
          disputeId
      );
      assertEquals("payment_dispute", String.valueOf(row.get("type")));
      assertEquals(customerA, row.get("customer_id"));
      assertEquals(projectA, row.get("project_id"));
      assertEquals(paymentNew, row.get("payment_id"));

      OffsetDateTime slaDueAt = (OffsetDateTime) row.get("sla_due_at");
      assertNotNull(slaDueAt);
      assertTrue(slaDueAt.isAfter(before.plusHours(47)));
      assertTrue(slaDueAt.isBefore(before.plusHours(49).plusMinutes(10)));

      Map<String, Object> p = jdbc.queryForMap("select case_id from payment where id = ?", paymentNew);
      assertEquals(caseA, p.get("case_id"));

      return null;
    });
  }

  @Test
  void testClientCrossCustomerNotFound() {
    UUID customerA = UUID.randomUUID();
    UUID customerB = UUID.randomUUID();

    UUID clientA = UUID.randomUUID();
    UUID clientB = UUID.randomUUID();
    UUID internalAdmin = UUID.randomUUID();

    UUID group = UUID.randomUUID();
    UUID projectA = UUID.randomUUID();
    UUID projectB = UUID.randomUUID();
    UUID caseA = UUID.randomUUID();
    UUID caseB = UUID.randomUUID();

    seedBase(customerA, customerB, clientA, clientB, internalAdmin, group, projectA, projectB, caseA, caseB);

    String internalToken = jwtService.sign(new AuthPrincipal(internalAdmin, true, "internal", "internal"));
    HttpHeaders internalHeaders = bearer(internalToken);
    UUID paymentB = createPayment(internalHeaders, projectB, caseB, new BigDecimal("200.00"), "BANK", "payerB", "5678", true);

    // control: internal can call internal APIs successfully
    assertNotNull(paymentB);
    UUID paymentB2 = correctPayment(internalHeaders, paymentB, "fixB");
    assertNotNull(paymentB2);

    String clientToken = jwtService.sign(new AuthPrincipal(clientA, false, "clientA", "client"));
    HttpHeaders clientHeaders = bearer(clientToken);

    // client cannot call internal APIs (403)
    ResponseEntity<String> internalCall = rest.postForEntity(
        "/payments",
        new HttpEntity<>(Map.of(), json(clientHeaders)),
        String.class
    );
    assertEquals(403, internalCall.getStatusCode().value());

    // cross-customer projectId/caseId -> 404 (no existence leakage)
    ResponseEntity<String> crossProject = rest.exchange(
        "/client/projects/" + projectB + "/payments",
        HttpMethod.GET,
        new HttpEntity<>(clientHeaders),
        String.class
    );
    assertEquals(404, crossProject.getStatusCode().value());
    assertNotNull(crossProject.getBody());
    assertTrue(crossProject.getBody().contains("NOT_FOUND"));

    ResponseEntity<String> crossCase = rest.exchange(
        "/client/projects/" + projectA + "/payments?caseId=" + caseB,
        HttpMethod.GET,
        new HttpEntity<>(clientHeaders),
        String.class
    );
    assertEquals(404, crossCase.getStatusCode().value());
    assertNotNull(crossCase.getBody());
    assertTrue(crossCase.getBody().contains("NOT_FOUND"));

    // cross-customer paymentId -> 404 (no permission denied / no 500)
    ResponseEntity<String> crossPaymentDispute = rest.exchange(
        "/client/payments/" + paymentB + "/disputes",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("title", "x", "message", "y"), json(clientHeaders)),
        String.class
    );
    assertEquals(404, crossPaymentDispute.getStatusCode().value());
    assertNotNull(crossPaymentDispute.getBody());
    assertTrue(crossPaymentDispute.getBody().contains("NOT_FOUND"));
  }

  private void seedBaseSingleCustomer(UUID customerA,
                                     UUID clientA,
                                     UUID internalAdmin,
                                     UUID group,
                                     UUID projectA,
                                     UUID caseA) {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      jdbc.update("insert into customer(id, name) values (?,?)", customerA, "C-A");
      jdbc.update("insert into app_group(id, name) values (?,?)", group, "G");

      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin, customer_id) values (?,?,?,?,?,?)",
          clientA, "13900000001", "clientA", "client", false, customerA);
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          internalAdmin, "13900000002", "internal", "internal", true);

      rlsSessionJdbc.applyRlsSession(internalAdmin.toString(), true, "");
      jdbc.update("insert into project(id, group_id, name, status, created_by, customer_id) values (?,?,?,?,?,?)",
          projectA, group, "P-A", "ACTIVE", internalAdmin, customerA);
      jdbc.update("insert into \"case\"(id, group_id, project_id, title, status, created_by) values (?,?,?,?,?,?)",
          caseA, group, projectA, "Case-A", "OPEN", internalAdmin);
      return null;
    });
  }

  private void seedBase(UUID customerA,
                        UUID customerB,
                        UUID clientA,
                        UUID clientB,
                        UUID internalAdmin,
                        UUID group,
                        UUID projectA,
                        UUID projectB,
                        UUID caseA,
                        UUID caseB) {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      jdbc.update("insert into customer(id, name) values (?,?)", customerA, "C-A");
      jdbc.update("insert into customer(id, name) values (?,?)", customerB, "C-B");
      jdbc.update("insert into app_group(id, name) values (?,?)", group, "G");

      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin, customer_id) values (?,?,?,?,?,?)",
          clientA, "13900000001", "clientA", "client", false, customerA);
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin, customer_id) values (?,?,?,?,?,?)",
          clientB, "13900000003", "clientB", "client", false, customerB);
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          internalAdmin, "13900000002", "internal", "internal", true);

      rlsSessionJdbc.applyRlsSession(internalAdmin.toString(), true, "");
      jdbc.update("insert into project(id, group_id, name, status, created_by, customer_id) values (?,?,?,?,?,?)",
          projectA, group, "P-A", "ACTIVE", internalAdmin, customerA);
      jdbc.update("insert into project(id, group_id, name, status, created_by, customer_id) values (?,?,?,?,?,?)",
          projectB, group, "P-B", "ACTIVE", internalAdmin, customerB);

      jdbc.update("insert into \"case\"(id, group_id, project_id, title, status, created_by) values (?,?,?,?,?,?)",
          caseA, group, projectA, "Case-A", "OPEN", internalAdmin);
      jdbc.update("insert into \"case\"(id, group_id, project_id, title, status, created_by) values (?,?,?,?,?,?)",
          caseB, group, projectB, "Case-B", "OPEN", internalAdmin);
      return null;
    });
  }

  private UUID createPayment(HttpHeaders internalHeaders,
                            UUID projectId,
                            UUID caseId,
                            BigDecimal amount,
                            String payChannel,
                            String payerName,
                            String bankLast4,
                            boolean isClientVisible) {
    Map<String, Object> req = new HashMap<>();
    req.put("projectId", projectId.toString());
    req.put("caseId", caseId.toString());
    req.put("amount", amount);
    req.put("paidAt", OffsetDateTime.now().toString());
    req.put("payChannel", payChannel);
    req.put("payerName", payerName);
    req.put("bankLast4", bankLast4);
    req.put("clientNote", "client-note");
    req.put("internalNote", "internal-note");
    req.put("isClientVisible", isClientVisible);

    ResponseEntity<Map> resp = rest.exchange(
        "/payments",
        HttpMethod.POST,
        new HttpEntity<>(req, json(internalHeaders)),
        Map.class
    );
    assertEquals(200, resp.getStatusCode().value());
    assertNotNull(resp.getBody());
    return UUID.fromString(String.valueOf(resp.getBody().get("paymentId")));
  }

  private UUID correctPayment(HttpHeaders internalHeaders, UUID oldPaymentId, String reason) {
    ResponseEntity<Map> resp = rest.exchange(
        "/payments/" + oldPaymentId + "/correct?reason=" + reason,
        HttpMethod.POST,
        new HttpEntity<>(json(internalHeaders)),
        Map.class
    );
    assertEquals(200, resp.getStatusCode().value());
    assertNotNull(resp.getBody());
    return UUID.fromString(String.valueOf(resp.getBody().get("newPaymentId")));
  }

  private HttpHeaders bearer(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }

  private HttpHeaders json(HttpHeaders headers) {
    HttpHeaders h = new HttpHeaders();
    h.putAll(headers);
    h.setContentType(MediaType.APPLICATION_JSON);
    return h;
  }

  private void assertPaymentWhitelist(Map<?, ?> pay) {
    Set<String> keys = pay.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    assertEquals(Set.of(
        "paymentId",
        "paidAt",
        "amount",
        "payChannel",
        "payerName",
        "bankLast4",
        "clientNote",
        "correctedFlag"
    ), keys);

    assertFalse(pay.containsKey("voucherFileId"));
    assertFalse(pay.containsKey("voucher_file_id"));
    assertFalse(pay.containsKey("internalNote"));
    assertFalse(pay.containsKey("internal_note"));
    assertFalse(pay.containsKey("groupId"));
    assertFalse(pay.containsKey("group_id"));
    assertFalse(pay.containsKey("caseId"));
    assertFalse(pay.containsKey("case_id"));
    assertFalse(pay.containsKey("correctedFromPaymentId"));
    assertFalse(pay.containsKey("corrected_from_payment_id"));
  }

  private void assertComplaintWhitelist(Map<?, ?> c) {
    Set<String> keys = c.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    assertEquals(Set.of(
        "id",
        "projectId",
        "paymentId",
        "status",
        "title",
        "message",
        "createdAt"
    ), keys);

    assertFalse(c.containsKey("customerId"));
    assertFalse(c.containsKey("customer_id"));
    assertFalse(c.containsKey("type"));
    assertFalse(c.containsKey("slaDueAt"));
    assertFalse(c.containsKey("sla_due_at"));
    assertFalse(c.containsKey("groupId"));
    assertFalse(c.containsKey("group_id"));
    assertFalse(c.containsKey("caseId"));
    assertFalse(c.containsKey("case_id"));
  }
}
