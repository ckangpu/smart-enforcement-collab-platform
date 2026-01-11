package com.secp.api.it;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.auth.JwtService;
import com.secp.api.infra.RlsSessionJdbc;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeAndReportIT extends IntegrationTestBase {

  @Autowired TestRestTemplate rest;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;
  @Autowired JwtService jwtService;
  @Autowired RlsSessionJdbc rlsSessionJdbc;

  @Test
  void meProjects_meTasks_andZoneDashboardReport_okAndRlsScoped() {
    UUID internalAdmin = UUID.randomUUID();
    UUID internalA = UUID.randomUUID();

    UUID groupA = UUID.randomUUID();
    UUID groupB = UUID.randomUUID();

    UUID projectA = UUID.randomUUID();
    UUID projectB = UUID.randomUUID();

    String expectedDayKey = DateTimeFormatter.BASIC_ISO_DATE.format(Instant.now().atZone(ZoneId.of("Asia/Shanghai")));

    seed(internalAdmin, internalA, groupA, groupB, projectA, projectB);

    HttpHeaders headersA = bearer(jwtService.sign(new AuthPrincipal(internalA, false, "internalA", "internal")));

    // create instruction+issue to generate a task assigned to issuer
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
    UUID instructionId = UUID.fromString(String.valueOf(created.getBody().get("instructionId")));

    HttpHeaders issueHeaders = json(headersA);
    issueHeaders.add("Idempotency-Key", "k-me-1");
    ResponseEntity<Map> issued = rest.exchange(
        "/instructions/" + instructionId + "/issue",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("targetCaseId", null), issueHeaders),
        Map.class
    );
    assertEquals(200, issued.getStatusCode().value());

    @SuppressWarnings("unchecked")
    List<String> taskIds = (List<String>) issued.getBody().get("taskIds");
    assertNotNull(taskIds);
    assertEquals(1, taskIds.size());
    String taskId = taskIds.getFirst();

    // /me/projects should include projectA (group scoped) and exclude projectB
    ResponseEntity<List<Map<String, Object>>> meProjects = rest.exchange(
        "/me/projects",
        HttpMethod.GET,
        new HttpEntity<>(json(headersA)),
        new ParameterizedTypeReference<>() {}
    );
    assertEquals(200, meProjects.getStatusCode().value());
    assertNotNull(meProjects.getBody());
    assertTrue(meProjects.getBody().stream().anyMatch(r -> projectA.toString().equals(String.valueOf(r.get("projectId")))));
    assertTrue(meProjects.getBody().stream().noneMatch(r -> projectB.toString().equals(String.valueOf(r.get("projectId")))));

    // /me/tasks should include the issued task
    ResponseEntity<List<Map<String, Object>>> meTasks = rest.exchange(
        "/me/tasks",
        HttpMethod.GET,
        new HttpEntity<>(json(headersA)),
        new ParameterizedTypeReference<>() {}
    );
    assertEquals(200, meTasks.getStatusCode().value());
    assertNotNull(meTasks.getBody());
    assertTrue(meTasks.getBody().stream().anyMatch(r -> taskId.equals(String.valueOf(r.get("taskId")))));

    // seed a payment to make report sum deterministic
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      rlsSessionJdbc.applyRlsSession(internalAdmin.toString(), true, "");

      // Create a case for payment (payment requires case_id); we can use any case under projectA
      UUID caseA = UUID.randomUUID();
      jdbc.update("insert into \"case\"(id, group_id, project_id, title, status, created_by) values (?,?,?,?,?,?)",
          caseA, groupA, projectA, "CA", "OPEN", internalAdmin);

      OffsetDateTime nowAt = OffsetDateTime.now();
      OffsetDateTime oldPaidAt = nowAt.minusDays(40);

      // Payment: 12.34 within 30d, 20.00 older than 30d
      jdbc.update("""
        insert into payment(id, group_id, project_id, case_id, amount, paid_at, pay_channel, payer_name, bank_last4,
                  corrected_from_payment_id, correction_reason, client_note, internal_note, is_client_visible, created_by)
        values (?,?,?,?,?, ?, 'BANK', 'payer', '1234', null, null, null, null, true, ?)
        """,
        UUID.randomUUID(), groupA, projectA, caseA, new BigDecimal("12.34"), nowAt, internalAdmin
      );
      jdbc.update("""
        insert into payment(id, group_id, project_id, case_id, amount, paid_at, pay_channel, payer_name, bank_last4,
                  corrected_from_payment_id, correction_reason, client_note, internal_note, is_client_visible, created_by)
        values (?,?,?,?,?, ?, 'BANK', 'payer', '1234', null, null, null, null, true, ?)
        """,
        UUID.randomUUID(), groupA, projectA, caseA, new BigDecimal("20.00"), oldPaidAt, internalAdmin
      );

      // Project denominators: projectA has denominators; projectA2 missing (to exercise missingDenominatorProjectCount)
      jdbc.update("update project set execution_target_amount=?, mandate_amount=? where id=?",
        new BigDecimal("100.00"), new BigDecimal("50.00"), projectA);
      UUID projectA2 = UUID.randomUUID();
      jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
        projectA2, groupA, "PA2", "ACTIVE", internalAdmin);

      // Instruction items: set one DONE and one overdue OPEN
      UUID itemDone = jdbc.queryForObject(
        "select id from instruction_item where instruction_id = ? order by created_at asc limit 1",
        UUID.class,
        instructionId
      );
      UUID itemOpen = jdbc.queryForObject(
        "select id from instruction_item where instruction_id = ? order by created_at desc limit 1",
        UUID.class,
        instructionId
      );
      assertNotNull(itemDone);
      assertNotNull(itemOpen);

      jdbc.update("update instruction_item set status='DONE', done_by=?, done_at=now() where id=?",
        internalAdmin, itemDone);
      jdbc.update("update instruction_item set status='OPEN', due_at=now() - interval '1 hour' where id=?", itemOpen);

      // Overdue outbox events for today (dayKey must be Asia/Shanghai)
      String dailyDedupe = "InstructionItem.OverdueDaily:item:" + itemOpen + ":" + expectedDayKey;
      String escDedupe = "InstructionItem.OverdueEscalate:item:" + itemOpen + ":" + expectedDayKey;
      String payload = "{\"dayKey\":\"" + expectedDayKey + "\",\"instructionItemId\":\"" + itemOpen + "\",\"instructionId\":\"" + instructionId + "\"}";

      jdbc.update("""
        insert into event_outbox(event_type, dedupe_key, group_id, project_id, case_id, actor_user_id, payload)
        values ('InstructionItem.OverdueDaily', ?, ?, ?, ?, null, ?::jsonb)
        on conflict (dedupe_key) do nothing
        """, dailyDedupe, groupA, projectA, caseA, payload);
      jdbc.update("""
        insert into event_outbox(event_type, dedupe_key, group_id, project_id, case_id, actor_user_id, payload)
        values ('InstructionItem.OverdueEscalate', ?, ?, ?, ?, null, ?::jsonb)
        on conflict (dedupe_key) do nothing
        """, escDedupe, groupA, projectA, caseA, payload);

      // Additional tasks to cover statusCounts
      jdbc.update("""
        insert into task(id, group_id, case_id, project_id, title, status, assignee_user_id, created_by)
        values (?,?,?,?,?,?,?,?)
        """,
        UUID.randomUUID(), groupA, caseA, projectA, "t-doing", "DOING", internalA, internalAdmin);
      jdbc.update("""
        insert into task(id, group_id, case_id, project_id, title, status, assignee_user_id, created_by)
        values (?,?,?,?,?,?,?,?)
        """,
        UUID.randomUUID(), groupA, caseA, projectA, "t-blocked", "BLOCKED", internalA, internalAdmin);
      jdbc.update("""
        insert into task(id, group_id, case_id, project_id, title, status, assignee_user_id, created_by)
        values (?,?,?,?,?,?,?,?)
        """,
        UUID.randomUUID(), groupA, caseA, projectA, "t-done", "DONE", internalA, internalAdmin);

      return null;
    });

    // /reports/zone-dashboard should be scoped to groupA (via app_group_ids)
    ResponseEntity<List<Map<String, Object>>> report = rest.exchange(
        "/reports/zone-dashboard",
        HttpMethod.GET,
        new HttpEntity<>(json(headersA)),
        new ParameterizedTypeReference<>() {}
    );
    assertEquals(200, report.getStatusCode().value());
    assertNotNull(report.getBody());
    assertEquals(1, report.getBody().size());
    assertEquals(groupA.toString(), String.valueOf(report.getBody().getFirst().get("groupId")));

    Map<String, Object> row = report.getBody().getFirst();

    // legacy fields still present
    Object effectivePaymentSum = row.get("effectivePaymentSum");
    assertNotNull(effectivePaymentSum);
    assertEquals(0, new BigDecimal("32.34").compareTo(new BigDecimal(String.valueOf(effectivePaymentSum))));

    // grouped: instruction
    @SuppressWarnings("unchecked")
    Map<String, Object> instruction = (Map<String, Object>) row.get("instruction");
    assertNotNull(instruction);
    assertEquals(2, ((Number) instruction.get("itemTotal")).intValue());
    assertEquals(1, ((Number) instruction.get("itemDone")).intValue());
    assertEquals(1, ((Number) instruction.get("itemOverdueCurrent")).intValue());
    assertEquals(0.5d, ((Number) instruction.get("itemDoneRate")).doubleValue(), 1e-9);

    // grouped: overdue (Asia/Shanghai dayKey)
    @SuppressWarnings("unchecked")
    Map<String, Object> overdue = (Map<String, Object>) row.get("overdue");
    assertNotNull(overdue);
    assertEquals(expectedDayKey, String.valueOf(overdue.get("dayKey")));
    assertEquals(1, ((Number) overdue.get("dailySentToday")).intValue());
    assertEquals(1, ((Number) overdue.get("escalateSentToday")).intValue());

    // grouped: task
    @SuppressWarnings("unchecked")
    Map<String, Object> task = (Map<String, Object>) row.get("task");
    assertNotNull(task);
    assertEquals(1, ((Number) task.get("projectOnlyCount")).intValue());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> statusCounts = (List<Map<String, Object>>) task.get("statusCounts");
    assertNotNull(statusCounts);
    assertTrue(statusCounts.stream().anyMatch(s -> "TODO".equals(String.valueOf(s.get("status"))) && ((Number) s.get("count")).intValue() == 1));
    assertTrue(statusCounts.stream().anyMatch(s -> "DOING".equals(String.valueOf(s.get("status"))) && ((Number) s.get("count")).intValue() == 1));
    assertTrue(statusCounts.stream().anyMatch(s -> "BLOCKED".equals(String.valueOf(s.get("status"))) && ((Number) s.get("count")).intValue() == 1));
    assertTrue(statusCounts.stream().anyMatch(s -> "DONE".equals(String.valueOf(s.get("status"))) && ((Number) s.get("count")).intValue() == 1));

    // grouped: payment
    @SuppressWarnings("unchecked")
    Map<String, Object> payment = (Map<String, Object>) row.get("payment");
    assertNotNull(payment);
    assertEquals(0, new BigDecimal("12.34").compareTo(new BigDecimal(String.valueOf(payment.get("sum30d")))));
    assertEquals(new BigDecimal("0.1234"), new BigDecimal(String.valueOf(payment.get("ratioTarget"))).setScale(4, RoundingMode.HALF_UP));
    assertEquals(new BigDecimal("0.2468"), new BigDecimal(String.valueOf(payment.get("ratioMandate"))).setScale(4, RoundingMode.HALF_UP));
    assertEquals(1, ((Number) payment.get("missingDenominatorProjectCount")).intValue());
  }

  private void seed(UUID internalAdmin,
                    UUID internalA,
                    UUID groupA,
                    UUID groupB,
                    UUID projectA,
                    UUID projectB) {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      jdbc.update("insert into app_group(id, name) values (?,?)", groupA, "GA");
      jdbc.update("insert into app_group(id, name) values (?,?)", groupB, "GB");

      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          internalAdmin, "13930000001", "admin", "internal", true);
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          internalA, "13930000002", "a", "internal", false);

      jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", internalA, groupA, "member");

      rlsSessionJdbc.applyRlsSession(internalAdmin.toString(), true, "");
      jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
          projectA, groupA, "PA", "ACTIVE", internalAdmin);
      jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
          projectB, groupB, "PB", "ACTIVE", internalAdmin);
      return null;
    });
  }

  private HttpHeaders bearer(String jwt) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(jwt);
    return h;
  }

  private HttpHeaders json(HttpHeaders base) {
    HttpHeaders h = new HttpHeaders();
    h.putAll(base);
    h.setContentType(MediaType.APPLICATION_JSON);
    h.setAccept(List.of(MediaType.APPLICATION_JSON));
    return h;
  }
}
