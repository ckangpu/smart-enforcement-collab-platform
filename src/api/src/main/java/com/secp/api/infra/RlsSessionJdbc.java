package com.secp.api.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class RlsSessionJdbc {

  private final JdbcTemplate jdbcTemplate;

  public void applyRlsSession(String userId, boolean isAdmin, String groupIdsCsv) {
    // Must be called AFTER transaction started, so SET LOCAL sticks to same connection.
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("RLS session must be applied inside a transaction");
    }

    // Use set_config to avoid SQL injection in session variables.
    jdbcTemplate.queryForObject("select set_config('app.user_id', ?, true)", String.class, userId == null ? "" : userId);
    jdbcTemplate.queryForObject("select set_config('app.is_admin', ?, true)", String.class, isAdmin ? "true" : "false");
    jdbcTemplate.queryForObject("select set_config('app.group_ids', ?, true)", String.class, groupIdsCsv == null ? "" : groupIdsCsv);
  }
}
