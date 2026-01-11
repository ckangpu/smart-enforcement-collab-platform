package com.secp.worker.it;

import com.secp.worker.OutboxPoller;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NotificationWorkerIT extends WorkerIntegrationTestBase {

  @Test
  void instructionIssued_createsNotifications_perItem_andConsumptionDedup_andNoCrossZoneLeak() {
    JdbcTemplate jdbc = jdbc();
    OutboxPoller poller = new OutboxPoller(jdbc);

    UUID groupA = UUID.randomUUID();
    UUID groupB = UUID.randomUUID();

    UUID admin = UUID.randomUUID();
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();

    seedCore(jdbc, groupA, groupB, admin, userA, userB);

    // instruction in groupA
    UUID projectA = UUID.randomUUID();
    jdbc.update("insert into project(id, group_id, name, status, created_by) values (?,?,?,?,?)",
        projectA, groupA, "PA", "ACTIVE", admin);

    UUID instructionId = UUID.randomUUID();
    jdbc.update("insert into instruction(id, group_id, ref_type, ref_id, title, status, created_by) values (?,?,?,?,?,?,?)",
        instructionId, groupA, "project", projectA, "instr", "ISSUED", userA);

    // item 1 -> userA (member of groupA)
    UUID item1 = UUID.randomUUID();
    jdbc.update("""
        insert into instruction_item(id, instruction_id, group_id, title, due_at, status, created_by, assignee_user_id)
        values (?,?,?,?,?,?,?,?)
        """,
        item1, instructionId, groupA, "i1", OffsetDateTime.now().plusDays(1), "OPEN", userA, userA);

    // item 2 -> userB (NOT member of groupA) => should NOT notify
    UUID item2 = UUID.randomUUID();
    jdbc.update("""
        insert into instruction_item(id, instruction_id, group_id, title, due_at, status, created_by, assignee_user_id)
        values (?,?,?,?,?,?,?,?)
        """,
        item2, instructionId, groupA, "i2", OffsetDateTime.now().plusDays(2), "OPEN", userA, userB);

    UUID eventId = UUID.randomUUID();
    jdbc.update("""
        insert into event_outbox(event_id, event_type, dedupe_key, group_id, project_id, case_id, actor_user_id, payload)
        values (?,?,?,?,?,?,?, ?::jsonb)
        """,
        eventId,
        "Instruction.Issued",
        "Instruction.Issued:instruction:" + instructionId + ":v1-it",
        groupA,
        projectA,
        null,
        null,
        "{\"instructionId\":\"" + instructionId + "\"}"
    );

    poller.pollOnce();

    Integer n1 = jdbc.queryForObject("select count(1) from notification where type='Instruction.Issued'", Integer.class);
    assertEquals(1, n1, "should notify only group member assignee (userA)");

    Integer consumptionDone = jdbc.queryForObject(
        "select count(1) from event_consumption where event_id=? and handler_name=? and status='done'",
        Integer.class,
        eventId,
        "NotificationHandler.v1"
    );
    assertEquals(1, consumptionDone);

    // replay same event by resetting outbox status to pending -> should be skipped by event_consumption unique
    jdbc.update("update event_outbox set status='pending', next_run_at=now(), processed_at=null where event_id=?", eventId);
    poller.pollOnce();

    Integer n2 = jdbc.queryForObject("select count(1) from notification where type='Instruction.Issued'", Integer.class);
    assertEquals(1, n2, "replay must not create duplicates");

    // make sure userB didn't get notified (cross-zone leak guard)
    Integer userBCount = jdbc.queryForObject("select count(1) from notification where user_id=?", Integer.class, userB);
    assertEquals(0, userBCount);

    // sanity: audit exists for notification
    Map<String, Object> audit = jdbc.queryForMap("select action, object_type from audit_log where object_type='notification' limit 1");
    assertTrue(String.valueOf(audit.get("action")).startsWith("Notification."));
    assertEquals("notification", audit.get("object_type"));
  }

  @Test
    void overdueDaily_createsNotification_andConsumptionDedup() {
    JdbcTemplate jdbc = jdbc();
    OutboxPoller poller = new OutboxPoller(jdbc);

    UUID groupA = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    UUID userA = UUID.randomUUID();
    seedCore(jdbc, groupA, UUID.randomUUID(), admin, userA, UUID.randomUUID());

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

    UUID e1 = UUID.randomUUID();
    jdbc.update("""
        insert into event_outbox(event_id, event_type, dedupe_key, group_id, project_id, case_id, actor_user_id, payload)
        values (?,?,?,?,?,?,?, ?::jsonb)
        """,
        e1,
        "InstructionItem.OverdueDaily",
        "InstructionItem.OverdueDaily:item:" + itemId + ":it-1",
        groupA,
        projectA,
        null,
        null,
        "{\"itemId\":\"" + itemId + "\",\"instructionId\":\"" + instructionId + "\",\"assigneeUserId\":\"" + userA + "\"}"
    );

    poller.pollOnce();

    Integer n1 = jdbc.queryForObject(
        "select count(1) from notification where user_id=? and type='InstructionItem.OverdueDaily'",
        Integer.class,
        userA
    );
    assertEquals(1, n1);

    // replay same event by resetting outbox status to pending -> must be skipped by event_consumption unique
    jdbc.update("update event_outbox set status='pending', next_run_at=now(), processed_at=null where event_id=?", e1);
    poller.pollOnce();

    Integer n2 = jdbc.queryForObject(
        "select count(1) from notification where user_id=? and type='InstructionItem.OverdueDaily'",
        Integer.class,
        userA
    );
    assertEquals(1, n2, "replay must not create duplicates");
  }

  private void seedCore(JdbcTemplate jdbc,
                        UUID groupA,
                        UUID groupB,
                        UUID admin,
                        UUID userA,
                        UUID userB) {
    jdbc.update("insert into app_group(id, name) values (?,?)", groupA, "GA");
    jdbc.update("insert into app_group(id, name) values (?,?)", groupB, "GB");

    jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
        admin, "13920000001", "admin", "internal", true);
    jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
        userA, "13920000002", "a", "internal", false);
    jdbc.update("insert into app_user(id, phone, username, user_type, is_admin) values (?,?,?,?,?)",
        userB, "13920000003", "b", "internal", false);

    jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", userA, groupA, "member");
    jdbc.update("insert into user_group(user_id, group_id, role_code) values (?,?,?)", userB, groupB, "member");
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
