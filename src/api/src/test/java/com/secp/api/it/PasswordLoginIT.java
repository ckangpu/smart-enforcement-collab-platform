package com.secp.api.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PasswordLoginIT extends IntegrationTestBase {

  @Autowired TestRestTemplate rest;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;

  @Test
  void passwordLogin_internal_ok_and_client_forbidden() {
    UUID internalUserId = UUID.randomUUID();
    UUID clientUserId = UUID.randomUUID();

    String internalUsername = "internal_user";
    String internalPhone = "13100000001";

    String clientUsername = "client_user";
    String clientPhone = "13100000002";

    String rawPassword = "admin123";
    String hash = new BCryptPasswordEncoder().encode(rawPassword);

    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      jdbc.update("select set_config('app.is_admin', 'true', true)");
      jdbc.update("select set_config('app.user_id', '', true)");
      jdbc.update("select set_config('app.group_ids', '', true)");

      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin, password_hash, password_updated_at) values (?,?,?,?,?,?, now())",
          internalUserId, internalPhone, internalUsername, "internal", false, hash);

      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          clientUserId, clientPhone, clientUsername, "client", false);

      return null;
    });

    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);

    // internal ok
    ResponseEntity<Map> ok = rest.postForEntity(
        "/auth/password/login",
        new HttpEntity<>(Map.of("username", internalUsername, "password", rawPassword), h),
        Map.class
    );
    assertEquals(200, ok.getStatusCode().value());
    assertNotNull(ok.getBody());
    assertNotNull(ok.getBody().get("token"));

    // wrong password -> 401
    ResponseEntity<String> badPwd = rest.postForEntity(
        "/auth/password/login",
        new HttpEntity<>(Map.of("username", internalUsername, "password", "wrong"), h),
        String.class
    );
    assertEquals(401, badPwd.getStatusCode().value());

    // client -> 403
    ResponseEntity<String> clientForbidden = rest.postForEntity(
        "/auth/password/login",
        new HttpEntity<>(Map.of("username", clientUsername, "password", "any"), h),
        String.class
    );
    assertEquals(403, clientForbidden.getStatusCode().value());
  }
}
