package com.secp.api.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class RlsIsolationIT extends IntegrationTestBase {

  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;

  @Test
  void userACannotSelectGroupBCase() {
    UUID groupA = UUID.randomUUID();
    UUID groupB = UUID.randomUUID();
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    UUID projectB = UUID.randomUUID();
    UUID caseB = UUID.randomUUID();

    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      // seed base tables
      jdbc.update("insert into app_group(id, name) values (?,?)", groupA, "A");
      jdbc.update("insert into app_group(id, name) values (?,?)", groupB, "B");
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          userA, "13000000001", "userA", "internal", false);
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          userB, "13000000002", "userB", "internal", false);
      jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", userA, groupA, "member");
      jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", userB, groupB, "member");

      // admin session for seeding RLS-protected tables
      jdbc.update("select set_config('app.is_admin', 'true', true)");
      jdbc.update("select set_config('app.user_id', '', true)");
      jdbc.update("select set_config('app.group_ids', '', true)");

      jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
          projectB, groupB, "PB", "ACTIVE", userB);
      jdbc.update("insert into \"case\"(id, group_id, project_id, title, status, created_by) values (?,?,?,?,?,?)",
          caseB, groupB, projectB, "CB", "OPEN", userB);
      return null;
    });

    Long visible = tx.execute(status -> {
      jdbc.update("select set_config('app.is_admin', 'false', true)");
      jdbc.update("select set_config('app.user_id', ?, true)", userA.toString());
      jdbc.update("select set_config('app.group_ids', ?, true)", groupA.toString());
      return jdbc.queryForObject("select count(*) from \"case\" where id=?", Long.class, caseB);
    });

    assertEquals(0L, visible);
  }
}
