package com.secp.worker.it;

import com.secp.worker.OutboxPoller;
import com.secp.worker.OverdueInstructionScanner;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverdueEscalationIT extends WorkerIntegrationTestBase {

  private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

  @Test
  void dailyThrottle_sameDay_scanTwice_onlyOneDailyEvent_andOneNotification() {
    JdbcTemplate jdbc = jdbc();

    UUID groupA = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    UUID assignee = UUID.randomUUID();
    UUID issuer = UUID.randomUUID();

    seedCore(jdbc, groupA, admin, assignee, issuer);

    UUID projectA = UUID.randomUUID();
    jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
        projectA, groupA, "PA", "ACTIVE", admin);

    UUID instructionId = UUID.randomUUID();
    jdbc.update("""
        insert into instruction(id, group_id, ref_type, ref_id, title, status, issued_by, issued_at, created_by)
        values (?,?,?,?,?,?,?,?,?)
        """,
        instructionId, groupA, "project", projectA, "instr", "ISSUED", issuer, OffsetDateTime.now(), assignee);

    UUID itemId = UUID.randomUUID();

    Instant fixed = Instant.parse("2026-01-11T00:10:00Z");
    Clock clock = Clock.fixed(fixed, ZoneOffset.UTC);
    OffsetDateTime dueAt = OffsetDateTime.ofInstant(fixed.minus(Duration.ofHours(2)), ZoneOffset.UTC);

    jdbc.update("""
        insert into instruction_item(id, instruction_id, group_id, title, due_at, status, created_by, assignee_user_id)
        values (?,?,?,?,?,?,?,?)
        """,
        itemId, instructionId, groupA, "i1", dueAt, "OPEN", assignee, assignee);

    OverdueInstructionScanner scanner = new OverdueInstructionScanner(jdbc, clock);
    scanner.scanOnce();
    scanner.scanOnce();

    String dayKey = ZonedDateTime.ofInstant(fixed, ZoneId.of("Asia/Shanghai")).format(DAY_FMT);
    String dedupeKey = "InstructionItem.OverdueDaily:item:" + itemId + ":" + dayKey;

    Integer dailyEvents = jdbc.queryForObject(
        "select count(1) from event_outbox where dedupe_key=? and event_type='InstructionItem.OverdueDaily'",
        Integer.class,
        dedupeKey
    );
    assertEquals(1, dailyEvents);

    OutboxPoller poller = new OutboxPoller(jdbc);
    poller.pollOnce();

    Integer dailyNotifs = jdbc.queryForObject(
        "select count(1) from notification where user_id=? and type='InstructionItem.OverdueDaily'",
        Integer.class,
        assignee
    );
    assertEquals(1, dailyNotifs);

    Integer legacyNotifs = jdbc.queryForObject(
        "select count(1) from notification where user_id=? and type='InstructionItem.Overdue'",
        Integer.class,
        assignee
    );
    assertEquals(0, legacyNotifs);
  }

  @Test
  void overdueOver24h_sameDay_createsEscalationToIssuer_oncePerDay() {
    JdbcTemplate jdbc = jdbc();

    UUID groupA = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    UUID assignee = UUID.randomUUID();
    UUID issuer = UUID.randomUUID();

    seedCore(jdbc, groupA, admin, assignee, issuer);

    UUID projectA = UUID.randomUUID();
    jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
        projectA, groupA, "PA", "ACTIVE", admin);

    UUID instructionId = UUID.randomUUID();
    jdbc.update("""
        insert into instruction(id, group_id, ref_type, ref_id, title, status, issued_by, issued_at, created_by)
        values (?,?,?,?,?,?,?,?,?)
        """,
        instructionId, groupA, "project", projectA, "instr", "ISSUED", issuer, OffsetDateTime.now(), assignee);

    UUID itemId = UUID.randomUUID();

    Instant fixed = Instant.parse("2026-01-11T01:00:00Z");
    Clock clock = Clock.fixed(fixed, ZoneOffset.UTC);
    OffsetDateTime dueAt = OffsetDateTime.ofInstant(fixed.minus(Duration.ofHours(25)), ZoneOffset.UTC);

    jdbc.update("""
        insert into instruction_item(id, instruction_id, group_id, title, due_at, status, created_by, assignee_user_id)
        values (?,?,?,?,?,?,?,?)
        """,
        itemId, instructionId, groupA, "i1", dueAt, "OPEN", assignee, assignee);

    OverdueInstructionScanner scanner = new OverdueInstructionScanner(jdbc, clock);
    scanner.scanOnce();

    String dayKey = ZonedDateTime.ofInstant(fixed, ZoneId.of("Asia/Shanghai")).format(DAY_FMT);

    Integer dailyEvents = jdbc.queryForObject(
        "select count(1) from event_outbox where dedupe_key=? and event_type='InstructionItem.OverdueDaily'",
        Integer.class,
        "InstructionItem.OverdueDaily:item:" + itemId + ":" + dayKey
    );
    assertEquals(1, dailyEvents);

    Integer escalateEvents = jdbc.queryForObject(
        "select count(1) from event_outbox where dedupe_key=? and event_type='InstructionItem.OverdueEscalate'",
        Integer.class,
        "InstructionItem.OverdueEscalate:item:" + itemId + ":" + dayKey
    );
    assertEquals(1, escalateEvents);

    OutboxPoller poller = new OutboxPoller(jdbc);
    poller.pollOnce();

    Integer dailyNotifs = jdbc.queryForObject(
        "select count(1) from notification where user_id=? and type='InstructionItem.OverdueDaily'",
        Integer.class,
        assignee
    );
    assertEquals(1, dailyNotifs);

    Integer escalateNotifs = jdbc.queryForObject(
        "select count(1) from notification where user_id=? and type='InstructionItem.OverdueEscalate'",
        Integer.class,
        issuer
    );
    assertEquals(1, escalateNotifs);
  }

  @Test
  void crossDay_scansCreateNewDailyAndEscalationEvents_perDay() {
    JdbcTemplate jdbc = jdbc();

    UUID groupA = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    UUID assignee = UUID.randomUUID();
    UUID issuer = UUID.randomUUID();

    seedCore(jdbc, groupA, admin, assignee, issuer);

    UUID projectA = UUID.randomUUID();
    jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
        projectA, groupA, "PA", "ACTIVE", admin);

    UUID instructionId = UUID.randomUUID();
    jdbc.update("""
        insert into instruction(id, group_id, ref_type, ref_id, title, status, issued_by, issued_at, created_by)
        values (?,?,?,?,?,?,?,?,?)
        """,
        instructionId, groupA, "project", projectA, "instr", "ISSUED", issuer, OffsetDateTime.now(), assignee);

    UUID itemId = UUID.randomUUID();

    Instant day1 = Instant.parse("2026-01-11T00:10:00Z");
    Instant day2 = Instant.parse("2026-01-12T00:10:00Z");

    OffsetDateTime dueAt = OffsetDateTime.ofInstant(day1.minus(Duration.ofHours(30)), ZoneOffset.UTC);

    jdbc.update("""
        insert into instruction_item(id, instruction_id, group_id, title, due_at, status, created_by, assignee_user_id)
        values (?,?,?,?,?,?,?,?)
        """,
        itemId, instructionId, groupA, "i1", dueAt, "OPEN", assignee, assignee);

    new OverdueInstructionScanner(jdbc, Clock.fixed(day1, ZoneOffset.UTC)).scanOnce();
    new OverdueInstructionScanner(jdbc, Clock.fixed(day2, ZoneOffset.UTC)).scanOnce();

    Integer dailyEventCount = jdbc.queryForObject(
        "select count(1) from event_outbox where event_type='InstructionItem.OverdueDaily'",
        Integer.class
    );
    assertEquals(2, dailyEventCount);

    Integer escalateEventCount = jdbc.queryForObject(
        "select count(1) from event_outbox where event_type='InstructionItem.OverdueEscalate'",
        Integer.class
    );
    assertEquals(2, escalateEventCount);

    OutboxPoller poller = new OutboxPoller(jdbc);
    poller.pollOnce();

    Integer dailyNotifCount = jdbc.queryForObject(
        "select count(1) from notification where user_id=? and type='InstructionItem.OverdueDaily'",
        Integer.class,
        assignee
    );
    assertEquals(2, dailyNotifCount);

    Integer escalateNotifCount = jdbc.queryForObject(
        "select count(1) from notification where user_id=? and type='InstructionItem.OverdueEscalate'",
        Integer.class,
        issuer
    );
    assertEquals(2, escalateNotifCount);
  }

  private void seedCore(JdbcTemplate jdbc, UUID groupA, UUID admin, UUID assignee, UUID issuer) {
    jdbc.update("insert into app_group(id, name) values (?,?)", groupA, "GA");

    jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
        admin, "13926000001", "admin", "internal", true);
    jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
        assignee, "13926000002", "assignee", "internal", false);
    jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
        issuer, "13926000003", "issuer", "internal", false);

    jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", assignee, groupA, "member");
    jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", issuer, groupA, "member");
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
