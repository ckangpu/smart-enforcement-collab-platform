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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InstructionTaskEvidenceIT extends IntegrationTestBase {

    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMddHH");

  @Autowired TestRestTemplate rest;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;
  @Autowired JwtService jwtService;
  @Autowired RlsSessionJdbc rlsSessionJdbc;

  @Test
  void issueIdempotent_projectOnlyTask_evidenceOk_payment422_rls404() {
    UUID internalAdmin = UUID.randomUUID();
    UUID internalA = UUID.randomUUID();
    UUID internalB = UUID.randomUUID();

    UUID groupA = UUID.randomUUID();
    UUID groupB = UUID.randomUUID();

    UUID projectA = UUID.randomUUID();
    UUID projectB = UUID.randomUUID();

    seed(internalAdmin, internalA, internalB, groupA, groupB, projectA, projectB);

    HttpHeaders headersA = bearer(jwtService.sign(new AuthPrincipal(internalA, false, "internalA", "internal")));
    HttpHeaders headersB = bearer(jwtService.sign(new AuthPrincipal(internalB, false, "internalB", "internal")));

    // 1) create instruction (project)
    Map<String, Object> createReq = Map.of(
        "refType", "project",
        "refId", projectA.toString(),
        "title", "instr",
        "items", List.of(Map.of(
            "title", "item-1",
            "dueAt", OffsetDateTime.now().plusDays(1).toString()
        ))
    );

    ResponseEntity<Map> created = rest.exchange(
        "/instructions",
        HttpMethod.POST,
        new HttpEntity<>(createReq, json(headersA)),
        Map.class
    );
    assertEquals(201, created.getStatusCode().value());
    assertNotNull(created.getBody());
    UUID instructionId = UUID.fromString(String.valueOf(created.getBody().get("instructionId")));

    // 2) issue instruction (idempotent)
    String idemKey = "k-1";
    HttpHeaders issueHeaders = json(headersA);
    issueHeaders.add("Idempotency-Key", idemKey);

    ResponseEntity<Map> issued1 = rest.exchange(
        "/instructions/" + instructionId + "/issue",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("targetCaseId", null), issueHeaders),
        Map.class
    );
    assertEquals(200, issued1.getStatusCode().value());
    assertNotNull(issued1.getBody());
    assertEquals(instructionId.toString(), String.valueOf(issued1.getBody().get("instructionId")));

    @SuppressWarnings("unchecked")
    List<String> taskIds1 = (List<String>) issued1.getBody().get("taskIds");
    assertNotNull(taskIds1);
    assertEquals(1, taskIds1.size());
    UUID taskId = UUID.fromString(taskIds1.getFirst());

    ResponseEntity<Map> issued2 = rest.exchange(
        "/instructions/" + instructionId + "/issue",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("targetCaseId", null), issueHeaders),
        Map.class
    );
    assertEquals(200, issued2.getStatusCode().value());
    assertNotNull(issued2.getBody());
    assertEquals(issued1.getBody(), issued2.getBody(), "Idempotency replay must return first response");

    // 3) DB asserts (admin session)
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      rlsSessionJdbc.applyRlsSession(internalAdmin.toString(), true, "");

      UUID itemId = jdbc.queryForObject(
          "select id from instruction_item where instruction_id = ?",
          UUID.class,
          instructionId
      );
      assertNotNull(itemId);

      Integer taskCount = jdbc.queryForObject(
          "select count(1) from task where instruction_item_id = ?",
          Integer.class,
          itemId
      );
      assertEquals(1, taskCount);

      Map<String, Object> taskRow = jdbc.queryForMap("select project_id, case_id from task where id = ?", taskId);
      assertEquals(projectA, taskRow.get("project_id"));
      assertNull(taskRow.get("case_id"));

      Integer outboxCount = jdbc.queryForObject(
          "select count(1) from event_outbox where dedupe_key = ?",
          Integer.class,
          "Instruction.Issued:instruction:" + instructionId + ":v1"
      );
      assertEquals(1, outboxCount);

      // 3.1) overdue worker hourly de-dupe (simulate insert twice)
      jdbc.update("update instruction_item set due_at = now() - interval '1 hour' where id = ?", itemId);
      String hourKey = ZonedDateTime.now(ZoneOffset.UTC).format(HOUR_FMT);
      String overdueDedupeKey = "InstructionItem.Overdue:instruction_item:" + itemId + ":h" + hourKey;
      String payload = "{\"instructionItemId\":\"" + itemId + "\",\"instructionId\":\"" + instructionId + "\"}";
      jdbc.update(
          """
          insert into event_outbox(event_type, dedupe_key, group_id, project_id, case_id, actor_user_id, payload)
          values ('InstructionItem.Overdue', ?, ?, ?, ?, null, ?::jsonb)
          on conflict (dedupe_key) do nothing
          """,
          overdueDedupeKey,
          groupA,
          projectA,
          null,
          payload
      );
      jdbc.update(
          """
          insert into event_outbox(event_type, dedupe_key, group_id, project_id, case_id, actor_user_id, payload)
          values ('InstructionItem.Overdue', ?, ?, ?, ?, null, ?::jsonb)
          on conflict (dedupe_key) do nothing
          """,
          overdueDedupeKey,
          groupA,
          projectA,
          null,
          payload
      );
      Integer overdueCount = jdbc.queryForObject(
          "select count(1) from event_outbox where dedupe_key = ?",
          Integer.class,
          overdueDedupeKey
      );
      assertEquals(1, overdueCount);
      return null;
    });

    // 4) payment redline: project-only task -> 422
    Map<String, Object> payReq = new HashMap<>();
    payReq.put("amount", new BigDecimal("12.34"));
    payReq.put("paidAt", OffsetDateTime.now().toString());
    payReq.put("payChannel", "BANK");
    payReq.put("payerName", "payer");
    payReq.put("bankLast4", "1234");
    payReq.put("clientNote", "c");
    payReq.put("internalNote", "i");
    payReq.put("isClientVisible", true);

    ResponseEntity<String> payResp = rest.exchange(
        "/tasks/" + taskId + "/payments",
        HttpMethod.POST,
        new HttpEntity<>(payReq, json(headersA)),
        String.class
    );
    assertEquals(422, payResp.getStatusCode().value());
    assertNotNull(payResp.getBody());
    assertTrue(payResp.getBody().contains("UNPROCESSABLE_ENTITY"));

    // 5) evidence project-only ok
    ResponseEntity<Map> ev = rest.exchange(
        "/evidences",
        HttpMethod.POST,
        new HttpEntity<>(Map.of(
            "projectId", projectA.toString(),
            "caseId", null,
            "title", "ev-1",
            "fileId", null
        ), json(headersA)),
        Map.class
    );
    assertEquals(201, ev.getStatusCode().value());
    assertNotNull(ev.getBody());
    UUID evidenceId = UUID.fromString(String.valueOf(ev.getBody().get("evidenceId")));

    tx.execute(status -> {
      rlsSessionJdbc.applyRlsSession(internalAdmin.toString(), true, "");
      Map<String, Object> evRow = jdbc.queryForMap("select group_id, project_id, case_id from evidence where id = ?", evidenceId);
      assertEquals(groupA, evRow.get("group_id"));
      assertEquals(projectA, evRow.get("project_id"));
      assertNull(evRow.get("case_id"));
      return null;
    });

    // 6) RLS: cross-zone user cannot issue (404, no leakage)
    HttpHeaders issueHeadersB = json(headersB);
    issueHeadersB.add("Idempotency-Key", "k-cross");
    ResponseEntity<String> cross = rest.exchange(
        "/instructions/" + instructionId + "/issue",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("targetCaseId", null), issueHeadersB),
        String.class
    );
    assertEquals(404, cross.getStatusCode().value());
    assertNotNull(cross.getBody());
    assertTrue(cross.getBody().contains("NOT_FOUND"));
  }

  private void seed(UUID internalAdmin,
                    UUID internalA,
                    UUID internalB,
                    UUID groupA,
                    UUID groupB,
                    UUID projectA,
                    UUID projectB) {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      jdbc.update("insert into app_group(id, name) values (?,?)", groupA, "GA");
      jdbc.update("insert into app_group(id, name) values (?,?)", groupB, "GB");

      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          internalAdmin, "13910000001", "admin", "internal", true);
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          internalA, "13910000002", "a", "internal", false);
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          internalB, "13910000003", "b", "internal", false);

      jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", internalA, groupA, "member");
      jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", internalB, groupB, "member");

      rlsSessionJdbc.applyRlsSession(internalAdmin.toString(), true, "");
      jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
          projectA, groupA, "PA", "ACTIVE", internalAdmin);
      jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
          projectB, groupB, "PB", "ACTIVE", internalAdmin);
      return null;
    });
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
}
