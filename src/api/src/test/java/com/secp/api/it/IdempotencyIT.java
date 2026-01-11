package com.secp.api.it;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.auth.JwtService;
import com.secp.api.payment.dto.CreatePaymentRequest;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyIT extends IntegrationTestBase {

  @Autowired TestRestTemplate rest;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;
  @Autowired JwtService jwtService;

  @Test
  void sameIdempotencyKeyReturnsSameResponseAndNoDuplicates() {
    UUID groupA = UUID.randomUUID();
    UUID userA = UUID.randomUUID();
    UUID projectA = UUID.randomUUID();
    UUID caseA = UUID.randomUUID();

    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      jdbc.update("insert into app_group(id, name) values (?,?)", groupA, "A");
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          userA, "13000000004", "userA3", "internal", false);
      jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", userA, groupA, "member");

      jdbc.update("select set_config('app.is_admin', 'true', true)");
      jdbc.update("select set_config('app.user_id', '', true)");
      jdbc.update("select set_config('app.group_ids', '', true)");

      jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
          projectA, groupA, "PA", "ACTIVE", userA);
      jdbc.update("insert into \"case\"(id, group_id, project_id, title, status, created_by) values (?,?,?,?,?,?)",
          caseA, groupA, projectA, "CA", "OPEN", userA);
      return null;
    });

    String token = jwtService.sign(new AuthPrincipal(userA, false, "userA3", "internal"));

    CreatePaymentRequest body = new CreatePaymentRequest(
        projectA,
        caseA,
        new BigDecimal("123.45"),
        OffsetDateTime.now(),
        "BANK",
        "payer",
        "4321",
        null,
        null,
        true
    );

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(token);
    headers.set("Idempotency-Key", "k1");

    ResponseEntity<Map> r1 = rest.postForEntity("/payments", new HttpEntity<>(body, headers), Map.class);
    ResponseEntity<Map> r2 = rest.postForEntity("/payments", new HttpEntity<>(body, headers), Map.class);

    assertEquals(200, r1.getStatusCode().value());
    assertEquals(200, r2.getStatusCode().value());
    assertEquals(r1.getBody(), r2.getBody());

    Long paymentCnt = tx.execute(status -> {
      jdbc.update("select set_config('app.is_admin', 'true', true)");
      jdbc.update("select set_config('app.user_id', '', true)");
      jdbc.update("select set_config('app.group_ids', '', true)");
      return jdbc.queryForObject("select count(*) from payment", Long.class);
    });

    Long outboxCnt = tx.execute(status -> {
      jdbc.update("select set_config('app.is_admin', 'true', true)");
      jdbc.update("select set_config('app.user_id', '', true)");
      jdbc.update("select set_config('app.group_ids', '', true)");
      return jdbc.queryForObject("select count(*) from event_outbox", Long.class);
    });

    Long auditCnt = tx.execute(status -> {
      jdbc.update("select set_config('app.is_admin', 'true', true)");
      jdbc.update("select set_config('app.user_id', '', true)");
      jdbc.update("select set_config('app.group_ids', '', true)");
      return jdbc.queryForObject("select count(*) from audit_log", Long.class);
    });

    assertEquals(1L, paymentCnt);
    assertEquals(1L, outboxCnt);
    assertEquals(1L, auditCnt);
  }

  @Test
  void idempotencyKeyWithDifferentRequestHashReturns409() {
    UUID groupA = UUID.randomUUID();
    UUID userA = UUID.randomUUID();
    UUID projectA = UUID.randomUUID();
    UUID caseA = UUID.randomUUID();

    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      jdbc.update("insert into app_group(id, name) values (?,?)", groupA, "A");
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          userA, "13000000005", "userA4", "internal", false);
      jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", userA, groupA, "member");

      jdbc.update("select set_config('app.is_admin', 'true', true)");
      jdbc.update("select set_config('app.user_id', '', true)");
      jdbc.update("select set_config('app.group_ids', '', true)");

      jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
          projectA, groupA, "PA", "ACTIVE", userA);
      jdbc.update("insert into \"case\"(id, group_id, project_id, title, status, created_by) values (?,?,?,?,?,?)",
          caseA, groupA, projectA, "CA", "OPEN", userA);
      return null;
    });

    String token = jwtService.sign(new AuthPrincipal(userA, false, "userA4", "internal"));

    CreatePaymentRequest body1 = new CreatePaymentRequest(
        projectA, caseA, new BigDecimal("10.00"), OffsetDateTime.now(), "BANK", "payer", null, null, null, true
    );
    CreatePaymentRequest body2 = new CreatePaymentRequest(
        projectA, caseA, new BigDecimal("11.00"), OffsetDateTime.now(), "BANK", "payer", null, null, null, true
    );

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(token);
    headers.set("Idempotency-Key", "k2");

    ResponseEntity<String> r1 = rest.postForEntity("/payments", new HttpEntity<>(body1, headers), String.class);
    ResponseEntity<String> r2 = rest.postForEntity("/payments", new HttpEntity<>(body2, headers), String.class);

    assertEquals(200, r1.getStatusCode().value());
    assertEquals(409, r2.getStatusCode().value());
    assertTrue(r2.getBody() != null && r2.getBody().contains("IDEMPOTENCY_KEY_REUSED_DIFFERENT_REQUEST"));
  }
}
