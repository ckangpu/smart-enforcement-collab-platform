package com.secp.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@EnableScheduling
@RequiredArgsConstructor
public class OutboxPoller {

  private final JdbcTemplate jdbc;

  @Value("${worker.batch-size:10}")
  private int batchSize = 10;

  @Value("${worker.max-retry:8}")
  private int maxRetry = 8;

  @Scheduled(fixedDelayString = "${worker.poll-ms:1000}")
  public void tick() {
    pollOnce();
  }

  @Transactional
  public void pollOnce() {
    // Worker in V1: use admin session to read/update outbox (simplify).
    // In production, use a dedicated DB role instead.
    jdbc.execute("SET LOCAL app.is_admin = 'true'");
    jdbc.execute("SET LOCAL app.user_id = ''");
    jdbc.execute("SET LOCAL app.group_ids = ''");

    List<Map<String, Object>> rows = jdbc.queryForList("""
        select event_id, event_type, payload
        from event_outbox
        where status='pending' and next_run_at <= now()
        order by created_at
        limit ?
        for update skip locked
        """, batchSize);

    for (Map<String, Object> r : rows) {
      UUID eventId = (UUID) r.get("event_id");
      String eventType = (String) r.get("event_type");
      String payload = String.valueOf(r.get("payload"));

      // mark processing
      jdbc.update("update event_outbox set status='processing' where event_id=?", eventId);

      try {
        handleEvent(eventId, eventType, payload);
        jdbc.update("update event_outbox set status='done', processed_at=now() where event_id=?", eventId);
      } catch (Exception ex) {
        int updated = jdbc.update("""
            update event_outbox
            set status = case when retry_count + 1 >= ? then 'failed' else 'pending' end,
                retry_count = retry_count + 1,
                last_error = left(?, 2000),
                next_run_at = case
                  when retry_count + 1 >= ? then now()
                  else now() + (interval '1 minute' * (2 ^ least(retry_count, 6)))
                end
            where event_id=?
            """,
            maxRetry,
            (ex.getClass().getSimpleName() + ":" + String.valueOf(ex.getMessage())),
            maxRetry,
            eventId);

        if (updated > 0 && (maxRetry <= 1)) {
          System.err.println("[worker] marked failed eventId=" + eventId);
        }
      }
    }
  }

  private void handleEvent(UUID eventId, String eventType, String payload) {
    if (isNotificationEvent(eventType)) {
      handleAsNotification(eventId, eventType);
      return;
    }

    // fallback demo handler
    String handlerName = "DemoHandler";
    if (!tryStartConsumption(eventId, handlerName)) {
      return;
    }

    System.out.println("[worker] handle " + eventType + " payload=" + payload);

    markConsumptionDone(eventId, handlerName);
  }

  private boolean isNotificationEvent(String eventType) {
    return "Instruction.Issued".equals(eventType)
        || "Task.Assigned".equals(eventType)
      || "InstructionItem.Overdue".equals(eventType)
      || "InstructionItem.OverdueDaily".equals(eventType)
      || "InstructionItem.OverdueEscalate".equals(eventType);
  }

  private void handleAsNotification(UUID eventId, String eventType) {
    String handlerName = "NotificationHandler.v1";
    if (!tryStartConsumption(eventId, handlerName)) {
      return;
    }

    try {
      if ("Instruction.Issued".equals(eventType)) {
        onInstructionIssued(eventId);
      } else if ("Task.Assigned".equals(eventType)) {
        onTaskAssigned(eventId);
      } else if ("InstructionItem.Overdue".equals(eventType)) {
        onInstructionItemOverdue(eventId);
      } else if ("InstructionItem.OverdueDaily".equals(eventType)) {
        onInstructionItemOverdueDaily(eventId);
      } else if ("InstructionItem.OverdueEscalate".equals(eventType)) {
        onInstructionItemOverdueEscalate(eventId);
      }
      markConsumptionDone(eventId, handlerName);
    } catch (Exception ex) {
      jdbc.update("update event_consumption set status='failed' where event_id=? and handler_name=?",
          eventId, handlerName);
      throw ex;
    }
  }

  private boolean tryStartConsumption(UUID eventId, String handlerName) {
    try {
      jdbc.update("insert into event_consumption(event_id, handler_name, status) values (?,?, 'started')",
          eventId, handlerName);
      return true;
    } catch (Exception dup) {
      return false;
    }
  }

  private void markConsumptionDone(UUID eventId, String handlerName) {
    jdbc.update("update event_consumption set status='done' where event_id=? and handler_name=?",
        eventId, handlerName);
  }

  private void onInstructionIssued(UUID eventId) {
    UUID instructionId = jdbc.queryForObject(
        "select (payload->>'instructionId')::uuid from event_outbox where event_id=?",
        UUID.class,
        eventId
    );
    if (instructionId == null) return;

    List<Map<String, Object>> items = jdbc.queryForList(
        """
        select id, group_id, due_at, coalesce(assignee_user_id, created_by) as assignee_user_id
        from instruction_item
        where instruction_id = ?
        order by created_at
        """,
        instructionId
    );

    for (Map<String, Object> it : items) {
      UUID itemId = (UUID) it.get("id");
      UUID groupId = (UUID) it.get("group_id");
      UUID assigneeUserId = (UUID) it.get("assignee_user_id");
      OffsetDateTime dueAt = (OffsetDateTime) it.get("due_at");
      if (groupId == null || assigneeUserId == null) continue;
      if (!isUserAllowedInGroup(assigneeUserId, groupId)) continue;

      String title = "新指令下发";
      String body = "instructionId=" + instructionId
          + ", itemId=" + itemId
          + (dueAt == null ? "" : ", dueAt=" + dueAt);
      String link = "/instructions/" + instructionId + "/items/" + itemId;

      upsertMergedNotification(groupId, assigneeUserId, "Instruction.Issued", title, body, link);
    }
  }

  private void onTaskAssigned(UUID eventId) {
    String instructionItemIdStr = jdbc.queryForObject(
        "select payload->>'instructionItemId' from event_outbox where event_id=?",
        String.class,
        eventId
    );
    // If the task was created from instruction issue, Instruction.Issued already notifies per item.
    if (instructionItemIdStr != null && !instructionItemIdStr.isBlank() && !"null".equalsIgnoreCase(instructionItemIdStr)) {
      return;
    }

    UUID taskId = jdbc.queryForObject(
        "select (payload->>'taskId')::uuid from event_outbox where event_id=?",
        UUID.class,
        eventId
    );
    if (taskId == null) return;

    List<Map<String, Object>> rows = jdbc.queryForList(
        "select group_id, assignee_user_id, title, plan_end from task where id=?",
        taskId
    );
    if (rows.isEmpty()) return;

    Map<String, Object> r = rows.getFirst();
    UUID groupId = (UUID) r.get("group_id");
    UUID assigneeUserId = (UUID) r.get("assignee_user_id");
    String taskTitle = String.valueOf(r.get("title"));
    OffsetDateTime planEnd = (OffsetDateTime) r.get("plan_end");

    if (groupId == null || assigneeUserId == null) return;
    if (!isUserAllowedInGroup(assigneeUserId, groupId)) return;

    String title = "新任务分配";
    String body = "taskId=" + taskId
        + ", title=" + safeText(taskTitle)
        + (planEnd == null ? "" : ", planEnd=" + planEnd);
    String link = "/tasks/" + taskId;

    upsertMergedNotification(groupId, assigneeUserId, "Task.Assigned", title, body, link);
  }

  private void onInstructionItemOverdue(UUID eventId) {
    // Legacy hourly event: keep consuming to not break older scanners, but do not notify (spam risk).
  }

    private void onInstructionItemOverdueDaily(UUID eventId) {
    UUID itemId = jdbc.queryForObject(
      "select (payload->>'itemId')::uuid from event_outbox where event_id=?",
      UUID.class,
      eventId
    );
    UUID instructionId = jdbc.queryForObject(
      "select (payload->>'instructionId')::uuid from event_outbox where event_id=?",
      UUID.class,
      eventId
    );
    if (itemId == null) return;

    List<Map<String, Object>> rows = jdbc.queryForList(
      """
      select ii.group_id,
           ii.due_at,
           coalesce(ii.assignee_user_id, ii.created_by) as assignee_user_id,
           ii.instruction_id
      from instruction_item ii
      where ii.id=?
      """,
      itemId
    );
    if (rows.isEmpty()) return;
    Map<String, Object> r = rows.getFirst();

    UUID groupId = (UUID) r.get("group_id");
    UUID assigneeUserId = (UUID) r.get("assignee_user_id");
    OffsetDateTime dueAt = (OffsetDateTime) r.get("due_at");
    UUID resolvedInstructionId = (UUID) r.get("instruction_id");
    UUID useInstructionId = instructionId != null ? instructionId : resolvedInstructionId;

    if (groupId == null || assigneeUserId == null) return;
    if (!isUserAllowedInGroup(assigneeUserId, groupId)) return;

    String title = "任务/指令超期提醒";
    String body = "instructionId=" + useInstructionId
      + ", itemId=" + itemId
      + (dueAt == null ? "" : ", dueAt=" + dueAt);
    String link = useInstructionId == null ? ("/instruction-items/" + itemId) : ("/instructions/" + useInstructionId + "/items/" + itemId);

    insertNotification(groupId, assigneeUserId, "InstructionItem.OverdueDaily", title, body, link);
    }

    private void onInstructionItemOverdueEscalate(UUID eventId) {
    UUID itemId = jdbc.queryForObject(
      "select (payload->>'itemId')::uuid from event_outbox where event_id=?",
      UUID.class,
      eventId
    );
    UUID instructionId = jdbc.queryForObject(
      "select (payload->>'instructionId')::uuid from event_outbox where event_id=?",
      UUID.class,
      eventId
    );
    UUID issuedByUserId = jdbc.queryForObject(
      "select (payload->>'issuedByUserId')::uuid from event_outbox where event_id=?",
      UUID.class,
      eventId
    );
    if (itemId == null || issuedByUserId == null) return;

    List<Map<String, Object>> rows = jdbc.queryForList(
      """
      select ii.group_id,
           ii.due_at,
           ii.instruction_id
      from instruction_item ii
      where ii.id=?
      """,
      itemId
    );
    if (rows.isEmpty()) return;
    Map<String, Object> r = rows.getFirst();

    UUID groupId = (UUID) r.get("group_id");
    OffsetDateTime dueAt = (OffsetDateTime) r.get("due_at");
    UUID resolvedInstructionId = (UUID) r.get("instruction_id");
    UUID useInstructionId = instructionId != null ? instructionId : resolvedInstructionId;

    if (groupId == null) return;
    if (!isUserAllowedInGroup(issuedByUserId, groupId)) return;

    String title = "超期升级提醒";
    String body = "instructionId=" + useInstructionId
      + ", itemId=" + itemId
      + (dueAt == null ? "" : ", dueAt=" + dueAt)
      + " (overdue>=24h)";
    String link = useInstructionId == null ? ("/instruction-items/" + itemId) : ("/instructions/" + useInstructionId + "/items/" + itemId);

    insertNotification(groupId, issuedByUserId, "InstructionItem.OverdueEscalate", title, body, link);
    }

  private void insertNotification(UUID groupId,
                                  UUID userId,
                                  String type,
                                  String title,
                                  String body,
                                  String link) {
    UUID newId = jdbc.queryForObject(
        """
        insert into notification(group_id, user_id, type, title, body, link)
        values (?,?,?,?,?,?)
        returning id
        """,
        UUID.class,
        groupId,
        userId,
        type,
        title,
        body,
        link
    );

    if (newId != null) {
      writeAudit(groupId, null, "Notification.Created", newId, type, link);
    }
  }

  private boolean isUserAllowedInGroup(UUID userId, UUID groupId) {
    Boolean isAdmin = jdbc.queryForObject("select is_admin from app_user where id=?", Boolean.class, userId);
    if (Boolean.TRUE.equals(isAdmin)) {
      return true;
    }
    Integer cnt = jdbc.queryForObject(
        "select count(1) from user_group where user_id=? and group_id=?",
        Integer.class,
        userId,
        groupId
    );
    return cnt != null && cnt > 0;
  }

  private void upsertMergedNotification(UUID groupId,
                                       UUID userId,
                                       String type,
                                       String title,
                                       String body,
                                       String link) {
    UUID existingId = jdbc.queryForObject(
        """
        select id
        from notification
        where user_id = ?
          and type = ?
          and (link is not distinct from ?)
          and status = 'unread'
          and created_at >= now() - interval '10 minutes'
        order by created_at desc
        limit 1
        """,
        UUID.class,
        userId, type, link
    );

    if (existingId != null) {
      jdbc.update(
          "update notification set title=?, body=?, updated_at=now() where id=?",
          title,
          body,
          existingId
      );
      writeAudit(groupId, null, "Notification.Merged", existingId, type, link);
      return;
    }

    UUID newId = jdbc.queryForObject(
        """
        insert into notification(group_id, user_id, type, title, body, link)
        values (?,?,?,?,?,?)
        returning id
        """,
        UUID.class,
        groupId,
        userId,
        type,
        title,
        body,
        link
    );

    if (newId != null) {
      writeAudit(groupId, null, "Notification.Created", newId, type, link);
    }
  }

  private void writeAudit(UUID groupId,
                          UUID actorUserId,
                          String action,
                          UUID notificationId,
                          String type,
                          String link) {
    jdbc.update(
        """
        insert into audit_log(group_id, actor_user_id, action, object_type, object_id, summary)
        values (?,?,?,?,?, ?::jsonb)
        """,
        groupId,
        actorUserId,
        action,
        "notification",
        notificationId,
        "{\"type\":\"" + safeJson(type) + "\",\"link\":\"" + safeJson(link) + "\"}"
    );
  }

  private String safeText(String s) {
    if (s == null) return "";
    String v = s.replace("\n", " ").replace("\r", " ");
    return v.length() > 80 ? v.substring(0, 80) : v;
  }

  private String safeJson(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
