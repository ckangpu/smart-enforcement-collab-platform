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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"rawtypes"})
class AdminBootstrapIT extends IntegrationTestBase {

  @Autowired TestRestTemplate rest;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;
  @Autowired JwtService jwtService;
  @Autowired RlsSessionJdbc rlsSessionJdbc;

  @Test
  void admin_canUpsertProjectAndCase_idempotent_andCaseInheritsGroup() {
    UUID admin = UUID.randomUUID();
    UUID groupA = UUID.randomUUID();

    seed(admin, groupA);

    HttpHeaders headers = bearer(jwtService.sign(new AuthPrincipal(admin, true, "admin", "internal")));

    UUID projectId = UUID.randomUUID();

    HttpHeaders h1 = json(headers);
    h1.add("Idempotency-Key", "p-1");

    ResponseEntity<Map> p1 = rest.exchange(
        "/admin/projects",
        HttpMethod.POST,
        new HttpEntity<>(Map.of(
            "projectId", projectId.toString(),
            "groupId", groupA.toString(),
            "name", "P-A",
            "executionTargetAmount", null,
            "mandateAmount", null
        ), h1),
        Map.class
    );
    assertEquals(200, p1.getStatusCode().value());
    Map p1Body = p1.getBody();
    assertNotNull(p1Body);
    assertEquals(projectId.toString(), String.valueOf(p1Body.get("projectId")));

    ResponseEntity<Map> p2 = rest.exchange(
        "/admin/projects",
        HttpMethod.POST,
        new HttpEntity<>(Map.of(
            "projectId", projectId.toString(),
            "groupId", groupA.toString(),
            "name", "P-A",
            "executionTargetAmount", null,
            "mandateAmount", null
        ), h1),
        Map.class
    );
    assertEquals(200, p2.getStatusCode().value());
    assertEquals(p1.getBody(), p2.getBody(), "Idempotency replay must return first response");

    UUID caseId = UUID.randomUUID();
    HttpHeaders h2 = json(headers);
    h2.add("Idempotency-Key", "c-1");

    ResponseEntity<Map> c1 = rest.exchange(
        "/admin/cases",
        HttpMethod.POST,
        new HttpEntity<>(Map.of(
            "caseId", caseId.toString(),
            "projectId", projectId.toString(),
            "title", "C-1"
        ), h2),
        Map.class
    );
    assertEquals(200, c1.getStatusCode().value());
    Map c1Body = c1.getBody();
    assertNotNull(c1Body);
    assertEquals(caseId.toString(), String.valueOf(c1Body.get("caseId")));

    TransactionTemplate tx = new TransactionTemplate(Objects.requireNonNull(txManager));
    tx.execute(status -> {
      rlsSessionJdbc.applyRlsSession(admin.toString(), true, "");

      Map<String, Object> pRow = jdbc.queryForMap("select group_id from project where id=?", projectId);
      assertEquals(groupA, pRow.get("group_id"));

      Map<String, Object> cRow = jdbc.queryForMap("select group_id, project_id from \"case\" where id=?", caseId);
      assertEquals(groupA, cRow.get("group_id"), "case.group_id must inherit project.group_id");
      assertEquals(projectId, cRow.get("project_id"));

      Integer outboxP = jdbc.queryForObject(
          "select count(1) from event_outbox where event_type='Admin.Project.Upserted' and project_id=?",
          Integer.class,
          projectId
      );
      assertEquals(1, outboxP);

      Integer outboxC = jdbc.queryForObject(
          "select count(1) from event_outbox where event_type='Admin.Case.Upserted' and case_id=?",
          Integer.class,
          caseId
      );
      assertEquals(1, outboxC);

      return null;
    });

    // members upsert smoke
    ResponseEntity<Map> pm = rest.exchange(
        "/admin/projects/" + projectId + "/members",
        HttpMethod.PUT,
        new HttpEntity<>(Map.of(
            "members", List.of(Map.of("userId", admin.toString(), "memberRole", "owner"))
        ), json(headers)),
        Map.class
    );
    assertEquals(200, pm.getStatusCode().value());

    ResponseEntity<Map> cm = rest.exchange(
        "/admin/cases/" + caseId + "/members",
        HttpMethod.PUT,
        new HttpEntity<>(Map.of(
            "members", List.of(Map.of("userId", admin.toString(), "memberRole", "assignee"))
        ), json(headers)),
        Map.class
    );
    assertEquals(200, cm.getStatusCode().value());
  }

  private void seed(UUID admin, UUID groupA) {
    TransactionTemplate tx = new TransactionTemplate(Objects.requireNonNull(txManager));
    tx.execute(status -> {
      jdbc.update("insert into app_group(id, name) values (?,?)", groupA, "GA");
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          admin, "13930000001", "admin", "internal", true);
      jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", admin, groupA, "member");
      return null;
    });
  }

  private HttpHeaders bearer(String token) {
    assertNotNull(token);
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
