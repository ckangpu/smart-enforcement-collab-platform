package com.secp.api.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class ZoneDashboardRepository {

  private final JdbcTemplate jdbc;

  public ZoneDashboardRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<ZoneDashboardRowDto> zoneDashboard(OffsetDateTime nowAt, String dayKey) {
    RowMapper<ZoneDashboardRowDto> mapper = (rs, rowNum) -> {
      UUID groupId = rs.getObject("group_id", UUID.class);
      long taskCount = rs.getLong("task_count");
      long overdueTaskCount = rs.getLong("overdue_task_count");
      Double overdueRate = taskCount == 0 ? null : (double) overdueTaskCount / (double) taskCount;
      int missingCount = taskCount == 0 ? 1 : 0;

      ZoneDashboardInstructionDto instruction = queryInstruction(groupId, nowAt);
      ZoneDashboardOverdueDto overdue = queryOverdue(groupId, dayKey);
      ZoneDashboardTaskDto task = queryTask(groupId);
      ZoneDashboardPaymentDto payment = queryPayment(groupId, nowAt);

      return new ZoneDashboardRowDto(
          groupId,
          rs.getString("group_name"),
          rs.getLong("project_count"),
          rs.getLong("case_count"),
          taskCount,
          overdueTaskCount,
          overdueRate,
          missingCount,
          rs.getBigDecimal("effective_payment_sum"),
          instruction,
          overdue,
          task,
          payment
      );
    };

    return jdbc.query(
        """
        select
          g.id as group_id,
          g.name as group_name,
          (select count(1) from project p where p.group_id = g.id) as project_count,
          (select count(1) from \"case\" c where c.group_id = g.id) as case_count,
          (select count(1) from task t where t.group_id = g.id) as task_count,
          (select count(1)
             from task t
            where t.group_id = g.id
              and t.plan_end is not null
              and t.status <> 'DONE'
              and t.plan_end < now()) as overdue_task_count,
          (select coalesce(sum(p.amount), 0)
             from payment p
            where p.group_id = g.id
              and not exists (select 1 from payment p2 where p2.corrected_from_payment_id = p.id)) as effective_payment_sum
        from app_group g
        where app_is_admin() or g.id = any(app_group_ids())
        order by g.created_at asc
        """,
        mapper
    );
  }

  private ZoneDashboardInstructionDto queryInstruction(UUID groupId, OffsetDateTime nowAt) {
    record Row(long itemTotal, long itemDone, long itemOverdueCurrent) {}

    Row row = jdbc.queryForObject(
        """
        select
          (select count(1) from instruction_item ii where ii.group_id = ?) as item_total,
          (select count(1) from instruction_item ii where ii.group_id = ? and ii.status = 'DONE') as item_done,
          (select count(1)
             from instruction_item ii
            where ii.group_id = ?
              and ii.status = 'OPEN'
              and ii.due_at is not null
              and ii.due_at < ?) as item_overdue_current
        """,
        (rs, rowNum) -> new Row(
            rs.getLong("item_total"),
            rs.getLong("item_done"),
            rs.getLong("item_overdue_current")
        ),
        groupId, groupId, groupId, nowAt
    );

    if (row == null) {
      return new ZoneDashboardInstructionDto(0, 0, null, 0);
    }

    Double itemDoneRate = row.itemTotal == 0 ? null : (double) row.itemDone / (double) row.itemTotal;
    return new ZoneDashboardInstructionDto(row.itemTotal, row.itemDone, itemDoneRate, row.itemOverdueCurrent);
  }

  private ZoneDashboardOverdueDto queryOverdue(UUID groupId, String dayKey) {
    record Row(long dailySentToday, long escalateSentToday) {}

    Row row = jdbc.queryForObject(
        """
        select
          (select count(1)
             from event_outbox eo
            where eo.group_id = ?
              and eo.event_type = 'InstructionItem.OverdueDaily'
              and coalesce(eo.payload->>'dayKey', to_char((eo.created_at at time zone 'Asia/Shanghai')::date, 'YYYYMMDD')) = ?) as daily_sent_today,
          (select count(1)
             from event_outbox eo
            where eo.group_id = ?
              and eo.event_type = 'InstructionItem.OverdueEscalate'
              and coalesce(eo.payload->>'dayKey', to_char((eo.created_at at time zone 'Asia/Shanghai')::date, 'YYYYMMDD')) = ?) as escalate_sent_today
        """,
        (rs, rowNum) -> new Row(
            rs.getLong("daily_sent_today"),
            rs.getLong("escalate_sent_today")
        ),
        groupId, dayKey, groupId, dayKey
    );

    if (row == null) {
      return new ZoneDashboardOverdueDto(dayKey, 0, 0);
    }
    return new ZoneDashboardOverdueDto(dayKey, row.dailySentToday, row.escalateSentToday);
  }

  private ZoneDashboardTaskDto queryTask(UUID groupId) {
    List<ZoneDashboardStatusCountDto> statusCounts = jdbc.query(
        """
        select t.status as status, count(1) as cnt
          from task t
         where t.group_id = ?
         group by t.status
         order by t.status asc
        """,
        (rs, rowNum) -> new ZoneDashboardStatusCountDto(rs.getString("status"), rs.getLong("cnt")),
        groupId
    );

    Long projectOnlyCount = jdbc.queryForObject(
        """
        select count(1)
          from task t
         where t.group_id = ?
           and t.case_id is null
        """,
        Long.class,
        groupId
    );

    return new ZoneDashboardTaskDto(
        statusCounts == null ? new ArrayList<>() : statusCounts,
        projectOnlyCount == null ? 0 : projectOnlyCount
    );
  }

  private ZoneDashboardPaymentDto queryPayment(UUID groupId, OffsetDateTime nowAt) {
    BigDecimal sum30d = jdbc.queryForObject(
        """
        select coalesce(sum(p.amount), 0)
          from payment p
         where p.group_id = ?
           and p.paid_at >= (? - interval '30 days')
           and not exists (select 1 from payment p2 where p2.corrected_from_payment_id = p.id)
        """,
        BigDecimal.class,
        groupId, nowAt
    );

    record DenomRow(BigDecimal targetSum, BigDecimal mandateSum, long missingCount) {}
    DenomRow denom = jdbc.queryForObject(
        """
        select
          coalesce(sum(p.execution_target_amount), 0) as target_sum,
          coalesce(sum(p.mandate_amount), 0) as mandate_sum,
          count(1) filter (where p.execution_target_amount is null or p.mandate_amount is null) as missing_count
        from project p
        where p.group_id = ?
        """,
        (rs, rowNum) -> new DenomRow(
            rs.getBigDecimal("target_sum"),
            rs.getBigDecimal("mandate_sum"),
            rs.getLong("missing_count")
        ),
        groupId
    );

    BigDecimal ratioTarget = null;
    BigDecimal ratioMandate = null;

    if (denom != null && denom.targetSum != null && denom.targetSum.compareTo(BigDecimal.ZERO) > 0) {
      ratioTarget = sum30d == null ? BigDecimal.ZERO : sum30d;
      ratioTarget = ratioTarget.divide(denom.targetSum, 4, java.math.RoundingMode.HALF_UP);
    }
    if (denom != null && denom.mandateSum != null && denom.mandateSum.compareTo(BigDecimal.ZERO) > 0) {
      ratioMandate = sum30d == null ? BigDecimal.ZERO : sum30d;
      ratioMandate = ratioMandate.divide(denom.mandateSum, 4, java.math.RoundingMode.HALF_UP);
    }

    return new ZoneDashboardPaymentDto(
        sum30d == null ? BigDecimal.ZERO : sum30d,
        ratioTarget,
        ratioMandate,
        denom == null ? 0 : denom.missingCount
    );
  }
}
