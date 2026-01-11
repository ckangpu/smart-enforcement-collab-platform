package com.secp.api.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class PaymentImmutabilityIT extends IntegrationTestBase {

  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager txManager;

  @Test
  void cannotUpdatePaymentCoreFields() {
    UUID groupA = UUID.randomUUID();
    UUID userA = UUID.randomUUID();
    UUID projectA = UUID.randomUUID();
    UUID caseA = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();

    TransactionTemplate tx = new TransactionTemplate(txManager);
    tx.execute(status -> {
      jdbc.update("insert into app_group(id, name) values (?,?)", groupA, "A");
      jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
          userA, "13000000003", "userA2", "internal", false);
      jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", userA, groupA, "member");

      jdbc.update("select set_config('app.is_admin', 'true', true)");
      jdbc.update("select set_config('app.user_id', '', true)");
      jdbc.update("select set_config('app.group_ids', '', true)");

      jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
          projectA, groupA, "PA", "ACTIVE", userA);
      jdbc.update("insert into \"case\"(id, group_id, project_id, title, status, created_by) values (?,?,?,?,?,?)",
          caseA, groupA, projectA, "CA", "OPEN", userA);

      jdbc.update("insert into payment(id, group_id, project_id, case_id, amount, paid_at, pay_channel, payer_name, bank_last4, created_by) values (?,?,?,?,?,?,?,?,?,?)",
          paymentId, groupA, projectA, caseA,
          new BigDecimal("100.00"), OffsetDateTime.now(), "BANK", "P", "1234", userA);
      return null;
    });

    assertThrows(DataAccessException.class, () -> tx.execute(status -> {
      jdbc.update("select set_config('app.is_admin', 'false', true)");
      jdbc.update("select set_config('app.user_id', ?, true)", userA.toString());
      jdbc.update("select set_config('app.group_ids', ?, true)", groupA.toString());
      jdbc.update("update payment set amount=? where id=?", new BigDecimal("200.00"), paymentId);
      return null;
    }));
  }
}
