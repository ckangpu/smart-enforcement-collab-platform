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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"rawtypes", "unchecked"})
class InstructionDetailAndStatusIT extends IntegrationTestBase {

  @Autowired TestRestTemplate rest;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;
  @Autowired JwtService jwtService;
  @Autowired RlsSessionJdbc rlsSessionJdbc;

  @Test
  void instructionDetail_andTaskDetail_andStatusChangedOutboxVersioned() {
    UUID internalAdmin = UUID.randomUUID();
    UUID internalA = UUID.randomUUID();
    UUID groupA = UUID.randomUUID();
    UUID projectA = UUID.randomUUID();

    seed(internalAdmin, internalA, groupA, projectA);

    HttpHeaders headersA = bearer(jwtService.sign(new AuthPrincipal(internalA, false, "internalA", "internal")));

    // create instruction
    ResponseEntity<Map> created = rest.exchange(
        "/instructions",
        HttpMethod.POST,
        new HttpEntity<>(Map.of(
            "refType", "project",
            "refId", projectA.toString(),
            "title", "instr",
            "items", List.of(Map.of(
                "title", "item-1",
                "dueAt", OffsetDateTime.now().plusDays(1).toString()
            ))
        ), json(headersA)),
        Map.class
    );
    assertEquals(201, created.getStatusCode().value());
    Map createdBody = created.getBody();
    assertNotNull(createdBody);
    UUID instructionId = UUID.fromString(String.valueOf(createdBody.get("instructionId")));

    // issue instruction -> creates task
    HttpHeaders issueHeaders = json(headersA);
    issueHeaders.add("Idempotency-Key", "k-issue");

    ResponseEntity<Map> issued = rest.exchange(
        "/instructions/" + instructionId + "/issue",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("targetCaseId", null), issueHeaders),
        Map.class
    );
    assertEquals(200, issued.getStatusCode().value());
    Map issuedBody = issued.getBody();
    assertNotNull(issuedBody);

    List<String> taskIds = (List<String>) issuedBody.get("taskIds");
    assertNotNull(taskIds);
    UUID taskId = UUID.fromString(taskIds.getFirst());

    // instruction detail
    ResponseEntity<Map> detail = rest.exchange(
        "/instructions/" + instructionId,
        HttpMethod.GET,
      new HttpEntity<Void>(Objects.requireNonNull(headersA)),
        Map.class
    );
    assertEquals(200, detail.getStatusCode().value());
    Map detailBody = detail.getBody();
    assertNotNull(detailBody);

    List<Map<String, Object>> items = (List<Map<String, Object>>) detailBody.get("items");
    assertNotNull(items);
    assertEquals(1, items.size());
    UUID itemId = UUID.fromString(String.valueOf(items.getFirst().get("instructionItemId")));
    assertEquals(taskId.toString(), String.valueOf(items.getFirst().get("taskId")));

    // task detail
    ResponseEntity<Map> taskDetail = rest.exchange(
        "/tasks/" + taskId,
        HttpMethod.GET,
      new HttpEntity<Void>(Objects.requireNonNull(headersA)),
        Map.class
    );
    assertEquals(200, taskDetail.getStatusCode().value());
    Map taskDetailBody = taskDetail.getBody();
    assertNotNull(taskDetailBody);
    assertEquals(projectA.toString(), String.valueOf(taskDetailBody.get("projectId")));
    assertNull(taskDetailBody.get("caseId"));

    // status change -> DONE then OPEN then no-op OPEN
    ResponseEntity<Map> s1 = rest.exchange(
        "/instruction-items/" + itemId + "/status",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("status", "DONE"), json(headersA)),
        Map.class
    );
    assertEquals(200, s1.getStatusCode().value());

    ResponseEntity<Map> s2 = rest.exchange(
        "/instruction-items/" + itemId + "/status",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("status", "OPEN"), json(headersA)),
        Map.class
    );
    assertEquals(200, s2.getStatusCode().value());

    ResponseEntity<Map> s3 = rest.exchange(
        "/instruction-items/" + itemId + "/status",
        HttpMethod.POST,
        new HttpEntity<>(Map.of("status", "OPEN"), json(headersA)),
        Map.class
    );
    assertEquals(200, s3.getStatusCode().value());

    TransactionTemplate tx = new TransactionTemplate(Objects.requireNonNull(txManager));
    tx.execute(status -> {
      rlsSessionJdbc.applyRlsSession(internalAdmin.toString(), true, "");
      Integer n = jdbc.queryForObject(
          "select count(1) from event_outbox where event_type='InstructionItem.StatusChanged' and payload->>'instructionItemId'=?",
          Integer.class,
          itemId.toString()
      );
      assertEquals(2, n, "only real status changes should enqueue events");
      return null;
    });
  }

  private void seed(UUID internalAdmin, UUID internalA, UUID groupA, UUID projectA) {
    TransactionTemplate tx = new TransactionTemplate(Objects.requireNonNull(txManager));
    tx.execute(status -> {
      jdbc.update("insert into app_group(id, name) values (?,?)", groupA, "GA");

      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          internalAdmin, "13940000001", "admin", "internal", true);
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          internalA, "13940000002", "a", "internal", false);

      jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", internalA, groupA, "member");

      rlsSessionJdbc.applyRlsSession(internalAdmin.toString(), true, "");
      jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
          projectA, groupA, "PA", "ACTIVE", internalAdmin);

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
