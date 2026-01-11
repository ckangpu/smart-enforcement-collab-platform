package com.secp.worker.it;

import com.secp.worker.OverdueInstructionScanner;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverdueInstructionScannerIT extends WorkerIntegrationTestBase {

  private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMddHH");

  @Test
  void scanOnce_sameHour_dedupeKeyStable_andNoDuplicateOutbox() {
    JdbcTemplate jdbc = jdbc();

    UUID groupA = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    UUID userA = UUID.randomUUID();

    seedCore(jdbc, groupA, admin, userA);

    UUID projectA = UUID.randomUUID();
    jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
        projectA, groupA, "PA", "ACTIVE", admin);

    UUID instructionId = UUID.randomUUID();
    jdbc.update("insert into instruction(id, group_id, ref_type, ref_id, title, status, created_by) values (?,?,?,?,?,?,?)",
        instructionId, groupA, "project", projectA, "instr", "ISSUED", userA);

    UUID itemId = UUID.randomUUID();
    jdbc.update("""
        insert into instruction_item(id, instruction_id, group_id, title, due_at, status, created_by, assignee_user_id)
        values (?,?,?,?,?,?,?,?)
        """,
        itemId, instructionId, groupA, "i1", OffsetDateTime.now().minusDays(1), "OPEN", userA, userA);

    // Fixed instant; hourKey must be computed in Asia/Shanghai, not UTC.
    Instant fixed = Instant.parse("2026-01-11T00:10:00Z");
    Clock clock = Clock.fixed(fixed, ZoneOffset.UTC);

    OverdueInstructionScanner scanner = new OverdueInstructionScanner(jdbc, clock);
    scanner.scanOnce();
    scanner.scanOnce();

    String hourKey = ZonedDateTime.ofInstant(fixed, ZoneId.of("Asia/Shanghai")).format(HOUR_FMT);
    String dedupeKey = "InstructionItem.Overdue:instruction_item:" + itemId + ":h" + hourKey;

    Integer byKey = jdbc.queryForObject(
        "select count(1) from event_outbox where dedupe_key=?",
        Integer.class,
        dedupeKey
    );
    assertEquals(1, byKey);

    Integer total = jdbc.queryForObject(
        "select count(1) from event_outbox where event_type='InstructionItem.Overdue'",
        Integer.class
    );
    assertEquals(1, total);
  }

  private void seedCore(JdbcTemplate jdbc, UUID groupA, UUID admin, UUID userA) {
    jdbc.update("insert into app_group(id, name) values (?,?)", groupA, "GA");

    jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
        admin, "13925000001", "admin", "internal", true);
    jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
        userA, "13925000002", "a", "internal", false);

    jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", userA, groupA, "member");
  }

  private JdbcTemplate jdbc() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setDriverClassName("org.postgresql.Driver");
    ds.setUrl(postgres.getJdbcUrl());
    ds.setUsername(postgres.getUsername());
    ds.setPassword(postgres.getPassword());
    return new JdbcTemplate(ds);
  }
}
