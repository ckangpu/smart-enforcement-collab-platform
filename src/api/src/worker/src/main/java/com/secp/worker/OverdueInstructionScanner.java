package com.secp.worker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OverdueInstructionScanner {

  static final ZoneId DEDUPE_ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMddHH");
  private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

  private final JdbcTemplate jdbc;
  private final Clock clock;

  @Autowired
  public OverdueInstructionScanner(JdbcTemplate jdbc) {
    this(jdbc, Clock.systemUTC());
  }

  public OverdueInstructionScanner(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Value("${worker.overdue-scan-ms:60000}")
  private long scanMs;

  @Value("${worker.overdue-batch-size:100}")
  private int batchSize;

  @Scheduled(fixedDelayString = "${worker.overdue-scan-ms:60000}")
  public void tick() {
    scanOnce();
  }

  @Transactional
  public void scanOnce() {
    jdbc.execute("SET LOCAL app.is_admin = 'true'");
    jdbc.execute("SET LOCAL app.user_id = ''");
    jdbc.execute("SET LOCAL app.group_ids = ''");

    ZonedDateTime nowZdt = ZonedDateTime.ofInstant(Instant.now(clock), DEDUPE_ZONE);
    OffsetDateTime nowAt = nowZdt.toOffsetDateTime();
    String hourKey = nowZdt.format(HOUR_FMT);
    String dayKey = nowZdt.format(DAY_FMT);

    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        select ii.id as instruction_item_id,
               ii.due_at,
           coalesce(ii.assignee_user_id, ii.created_by) as assignee_user_id,
               i.id as instruction_id,
               i.group_id,
               i.issued_by,
               i.ref_type,
               case when i.ref_type='project' then i.ref_id else c.project_id end as project_id,
               case when i.ref_type='case' then i.ref_id else null end as case_id
          from instruction_item ii
          join instruction i on i.id = ii.instruction_id
          left join "case" c on c.id = i.ref_id and i.ref_type = 'case'
         where ii.status <> 'DONE'
           and ii.due_at is not null
           and ii.due_at < ?
         order by ii.due_at
         limit ?
        """,
        nowAt,
        batchSize
    );

    for (Map<String, Object> r : rows) {
      UUID itemId = (UUID) r.get("instruction_item_id");
      UUID instructionId = (UUID) r.get("instruction_id");
      UUID groupId = (UUID) r.get("group_id");
      UUID projectId = (UUID) r.get("project_id");
      UUID caseId = (UUID) r.get("case_id");
      UUID issuedByUserId = (UUID) r.get("issued_by");
      UUID assigneeUserId = (UUID) r.get("assignee_user_id");
      OffsetDateTime dueAt = (OffsetDateTime) r.get("due_at");

      // ---- keep existing hourly event (do not break) ----
      String hourlyDedupeKey = "InstructionItem.Overdue:instruction_item:" + itemId + ":h" + hourKey;

      // V1 payload for backward compatibility
      String hourlyPayload = "{\"instructionItemId\":\"" + itemId + "\",\"instructionId\":\"" + instructionId + "\"}";

      jdbc.update(
          """
          insert into event_outbox(event_type, dedupe_key, group_id, project_id, case_id, actor_user_id, payload)
          values ('InstructionItem.Overdue', ?, ?, ?, ?, null, ?::jsonb)
          on conflict (dedupe_key) do nothing
          """,
          hourlyDedupeKey,
          groupId,
          projectId,
          caseId,
          hourlyPayload
      );

      // ---- daily throttle: 1 per day per item (Asia/Shanghai) ----
      String dailyDedupeKey = "InstructionItem.OverdueDaily:item:" + itemId + ":" + dayKey;
      String dailyPayload = buildPayload(
          itemId,
          instructionId,
          assigneeUserId,
          issuedByUserId,
          dueAt,
          nowAt,
          dayKey
      );
      jdbc.update(
          """
          insert into event_outbox(event_type, dedupe_key, group_id, project_id, case_id, actor_user_id, payload)
          values ('InstructionItem.OverdueDaily', ?, ?, ?, ?, null, ?::jsonb)
          on conflict (dedupe_key) do nothing
          """,
          dailyDedupeKey,
          groupId,
          projectId,
          caseId,
          dailyPayload
      );

      // ---- escalation: overdue >= 24h, 1 per day per item ----
      if (issuedByUserId != null && dueAt != null) {
        Duration overdue = Duration.between(dueAt.toInstant(), nowAt.toInstant());
        if (!overdue.isNegative() && overdue.compareTo(Duration.ofHours(24)) >= 0) {
          String escalateDedupeKey = "InstructionItem.OverdueEscalate:item:" + itemId + ":" + dayKey;
          String escalatePayload = buildPayload(
              itemId,
              instructionId,
              assigneeUserId,
              issuedByUserId,
              dueAt,
              nowAt,
              dayKey
          );
          jdbc.update(
              """
              insert into event_outbox(event_type, dedupe_key, group_id, project_id, case_id, actor_user_id, payload)
              values ('InstructionItem.OverdueEscalate', ?, ?, ?, ?, null, ?::jsonb)
              on conflict (dedupe_key) do nothing
              """,
              escalateDedupeKey,
              groupId,
              projectId,
              caseId,
              escalatePayload
          );
        }
      }
    }
  }

  private String buildPayload(UUID itemId,
                              UUID instructionId,
                              UUID assigneeUserId,
                              UUID issuedByUserId,
                              OffsetDateTime dueAt,
                              OffsetDateTime nowAt,
                              String dayKey) {
    return "{"
        + "\"itemId\":\"" + itemId + "\""
        + ",\"instructionId\":\"" + instructionId + "\""
        + ",\"assigneeUserId\":" + (assigneeUserId == null ? "null" : ("\"" + assigneeUserId + "\""))
        + ",\"issuedByUserId\":" + (issuedByUserId == null ? "null" : ("\"" + issuedByUserId + "\""))
        + ",\"dueAt\":" + (dueAt == null ? "null" : ("\"" + dueAt + "\""))
        + ",\"nowAt\":" + (nowAt == null ? "null" : ("\"" + nowAt + "\""))
        + ",\"dayKey\":\"" + safeJson(dayKey) + "\""
        + "}";
  }

  private String safeJson(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
