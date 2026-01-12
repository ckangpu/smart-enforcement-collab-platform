package com.secp.api.project;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProjectDetailRepository {

  private final JdbcTemplate jdbc;

  public ProjectDetailRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Map<String, Object>> findProject(UUID projectId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        select id, name, group_id, biz_tags, mandate_amount, execution_target_amount, status, created_at, updated_at
          from project
         where id = ?
        """,
        projectId
    );
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(rows.getFirst());
  }

  public List<Map<String, Object>> listCases(UUID projectId) {
    return jdbc.queryForList(
        """
        select id, title, status, created_at
          from \"case\"
         where project_id = ?
         order by created_at desc
        """,
        projectId
    );
  }

  public List<Map<String, Object>> listTasks(UUID projectId) {
    return jdbc.queryForList(
        """
        select id, title, status, priority, case_id, instruction_item_id, plan_end, assignee_user_id
          from task
         where project_id = ?
         order by created_at desc
         limit 500
        """,
        projectId
    );
  }

  public List<Map<String, Object>> listProjectMembers(UUID projectId) {
    return jdbc.queryForList(
        """
        select pm.user_id, pm.member_role, u.username, u.phone
          from project_member pm
          join app_user u on u.id = pm.user_id
         where pm.project_id = ?
         order by pm.created_at
        """,
        projectId
    );
  }

  public List<Map<String, Object>> listCaseMembers(UUID projectId) {
    return jdbc.queryForList(
        """
        select c.id as case_id, cm.user_id, cm.member_role, u.username, u.phone
          from \"case\" c
          join case_member cm on cm.case_id = c.id
          join app_user u on u.id = cm.user_id
         where c.project_id = ?
         order by c.created_at, cm.created_at
        """,
        projectId
    );
  }

  public record InstructionAggRow(UUID instructionId,
                                  String title,
                                  String status,
                                  int version,
                                  OffsetDateTime deadline,
                                  OffsetDateTime issuedAt,
                                  int itemTotal,
                                  int itemDone,
                                  int itemOverdue) {
  }

  public List<InstructionAggRow> listInstructionAgg(UUID projectId) {
    return jdbc.query(
        """
        select i.id,
               i.title,
               i.status,
               i.version,
               max(ii.due_at) as deadline,
               i.issued_at,
               count(ii.id) as item_total,
               sum(case when ii.status = 'DONE' then 1 else 0 end) as item_done,
               sum(case when ii.status <> 'DONE' and ii.due_at is not null and ii.due_at < now() then 1 else 0 end) as item_overdue
          from instruction i
          left join instruction_item ii on ii.instruction_id = i.id
         where (i.ref_type = 'project' and i.ref_id = ?)
            or (i.ref_type = 'case' and i.ref_id in (select id from \"case\" where project_id = ?))
         group by i.id, i.title, i.status, i.version, i.issued_at
         order by max(i.created_at) desc
         limit 500
        """,
        (rs, rowNum) -> new InstructionAggRow(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getObject("deadline", OffsetDateTime.class),
            rs.getObject("issued_at", OffsetDateTime.class),
            rs.getInt("item_total"),
            rs.getInt("item_done"),
            rs.getInt("item_overdue")
        ),
        projectId,
        projectId
    );
  }

  public record PaymentsAggRow(BigDecimal sumAll, BigDecimal sum30d, int effectiveCount, OffsetDateTime latestPaidAt) {
  }

  public PaymentsAggRow getPaymentsAgg(UUID projectId) {
    Map<String, Object> row = jdbc.queryForMap(
        """
        select
          coalesce(sum(p.amount), 0) as sum_all,
          coalesce(sum(case when p.paid_at >= now() - interval '30 days' then p.amount else 0 end), 0) as sum_30d,
          count(1) as effective_count,
          max(p.paid_at) as latest_paid_at
        from payment p
        where p.project_id = ?
          and not exists (select 1 from payment p2 where p2.corrected_from_payment_id = p.id)
        """,
        projectId
    );

    return new PaymentsAggRow(
        (BigDecimal) row.get("sum_all"),
        (BigDecimal) row.get("sum_30d"),
        ((Number) row.get("effective_count")).intValue(),
        toOffsetDateTime(row.get("latest_paid_at"))
    );
  }

  private static OffsetDateTime toOffsetDateTime(Object v) {
    if (v == null) return null;
    if (v instanceof OffsetDateTime odt) return odt;
    if (v instanceof Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
    return OffsetDateTime.parse(String.valueOf(v));
  }
}
