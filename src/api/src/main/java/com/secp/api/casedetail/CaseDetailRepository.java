package com.secp.api.casedetail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CaseDetailRepository {

  private final JdbcTemplate jdbc;

  public CaseDetailRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Map<String, Object>> findCaseWithProject(UUID caseId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        select c.id as case_id,
               c.code as case_code,
               c.accepted_at as case_accepted_at,
               c.title as case_title,
               c.status as case_status,
               c.group_id as case_group_id,
               c.project_id as project_id,
               c.created_at as case_created_at,
               c.updated_at as case_updated_at,
               p.code as project_code,
               p.name as project_name
          from "case" c
          join project p on p.id = c.project_id
         where c.id = ?
        """,
        caseId
    );
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(rows.getFirst());
  }

  public List<Map<String, Object>> listMembers(UUID caseId) {
    return jdbc.queryForList(
        """
        select cm.user_id,
               cm.member_role,
               u.username,
               u.phone
          from case_member cm
          join app_user u on u.id = cm.user_id
         where cm.case_id = ?
         order by cm.created_at
        """,
        caseId
    );
  }

  public List<Map<String, Object>> listTasks(UUID caseId) {
    return jdbc.queryForList(
        """
        select t.id,
               t.title,
               t.status,
               t.priority,
               t.plan_end,
               t.assignee_user_id
          from task t
         where t.case_id = ?
         order by t.created_at desc
         limit 500
        """,
        caseId
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

  public List<InstructionAggRow> listInstructionAgg(UUID caseId) {
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
         where i.ref_type = 'case' and i.ref_id = ?
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
        caseId
    );
  }

  public record PaymentsAggRow(BigDecimal sumAll, BigDecimal sum30d, int effectiveCount, OffsetDateTime latestPaidAt) {
  }

  public PaymentsAggRow getPaymentsAgg(UUID caseId) {
    Map<String, Object> row = jdbc.queryForMap(
        """
        select
          coalesce(sum(p.amount), 0) as sum_all,
          coalesce(sum(case when p.paid_at >= now() - interval '30 days' then p.amount else 0 end), 0) as sum_30d,
          count(1) as effective_count,
          max(p.paid_at) as latest_paid_at
        from payment p
        where p.case_id = ?
          and not exists (select 1 from payment p2 where p2.corrected_from_payment_id = p.id)
        """,
        caseId
    );

    return new PaymentsAggRow(
        (BigDecimal) row.get("sum_all"),
        (BigDecimal) row.get("sum_30d"),
        ((Number) row.get("effective_count")).intValue(),
        row.get("latest_paid_at") == null ? null : (OffsetDateTime) row.get("latest_paid_at")
    );
  }
}
