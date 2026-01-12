package com.secp.api.project;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
        select id, code, accepted_at, name, group_id, biz_tags, mandate_amount, execution_target_amount, status, created_at, updated_at
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
        select id, code, accepted_at, title, status, created_at
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

  // -------------------- Workbench aggregations (read-only; RLS applies) --------------------

  public int countWbCreditors(UUID projectId) {
    Integer v = jdbc.queryForObject(
        """
        select count(1)
          from project_creditor
         where project_id = ?
        """,
        Integer.class,
        projectId
    );
    return v == null ? 0 : v;
  }

  public List<String> listWbCreditorNames(UUID projectId, int limit) {
    return jdbc.query(
        """
        select name
          from project_creditor
         where project_id = ?
         order by created_at desc
         limit ?
        """,
        (rs, rn) -> rs.getString("name"),
        projectId,
        limit
    );
  }

  public int countWbDebtors(UUID projectId) {
    Integer v = jdbc.queryForObject(
        """
        select count(1)
          from project_debtor
         where project_id = ?
        """,
        Integer.class,
        projectId
    );
    return v == null ? 0 : v;
  }

  public List<String> listWbDebtorNames(UUID projectId, int limit) {
    return jdbc.query(
        """
        select name
          from project_debtor
         where project_id = ?
         order by created_at desc
         limit ?
        """,
        (rs, rn) -> rs.getString("name"),
        projectId,
        limit
    );
  }

  public record WbCaseBasisAgg(int caseTotal, int basisFilled) {
  }

  public WbCaseBasisAgg getWbCaseBasisAgg(UUID projectId) {
    Map<String, Object> row = jdbc.queryForMap(
        """
        select
          count(1) as case_total,
          sum(
            case when (basis_doc_type is not null
                   or basis_doc_no is not null
                   or basis_org is not null
                   or basis_main_text is not null)
            then 1 else 0 end
          ) as basis_filled
        from "case"
        where project_id = ?
        """,
        projectId
    );
    int total = ((Number) row.get("case_total")).intValue();
    int filled = row.get("basis_filled") == null ? 0 : ((Number) row.get("basis_filled")).intValue();
    return new WbCaseBasisAgg(total, filled);
  }

  public record WbCategoryCount(String category, int cnt) {
  }

  public int countWbClues(UUID projectId) {
    Integer v = jdbc.queryForObject(
        """
        select count(1)
          from debtor_clue dc
          join project_debtor d on d.id = dc.debtor_id
         where d.project_id = ?
        """,
        Integer.class,
        projectId
    );
    return v == null ? 0 : v;
  }

  public List<WbCategoryCount> listWbClueCategoryCounts(UUID projectId) {
    return jdbc.query(
        """
        select dc.category, count(1) as cnt
          from debtor_clue dc
          join project_debtor d on d.id = dc.debtor_id
         where d.project_id = ?
         group by dc.category
         order by cnt desc, dc.category
        """,
        (rs, rn) -> new WbCategoryCount(rs.getString("category"), rs.getInt("cnt")),
        projectId
    );
  }

  public record WbClueTopRow(String category, String detail, String debtorName) {
  }

  public List<WbClueTopRow> listWbClueTop(UUID projectId, int limit) {
    return jdbc.query(
        """
        select dc.category, dc.detail, d.name as debtor_name
          from debtor_clue dc
          join project_debtor d on d.id = dc.debtor_id
         where d.project_id = ?
         order by dc.created_at desc
         limit ?
        """,
        (rs, rn) -> new WbClueTopRow(rs.getString("category"), rs.getString("detail"), rs.getString("debtor_name")),
        projectId,
        limit
    );
  }

  public record WbCaseProcedureAgg(UUID caseId, String caseCode, String caseTitle, int procedureCount, LocalDate latestDecidedAt) {
  }

  public List<WbCaseProcedureAgg> listWbProcedureAggByCase(UUID projectId) {
    return jdbc.query(
        """
        select c.id as case_id,
               c.code as case_code,
               c.title as case_title,
               count(cp.id) as procedure_count,
               max(cp.decided_at) as latest_decided_at
          from "case" c
          left join case_procedure cp on cp.case_id = c.id
         where c.project_id = ?
         group by c.id, c.code, c.title
         order by max(c.created_at) desc
        """,
        (rs, rn) -> new WbCaseProcedureAgg(
            rs.getObject("case_id", UUID.class),
            rs.getString("case_code"),
            rs.getString("case_title"),
            rs.getInt("procedure_count"),
            rs.getObject("latest_decided_at", LocalDate.class)
        ),
        projectId
    );
  }

  public record WbMeasureAgg(int total, int dueCount) {
  }

  public WbMeasureAgg getWbControlMeasureAgg(UUID projectId) {
    Map<String, Object> row = jdbc.queryForMap(
        """
        select
          count(1) as total,
          sum(case when m.due_at is not null and m.due_at <= current_date then 1 else 0 end) as due_count
        from case_measure_control m
        join "case" c on c.id = m.case_id
        where c.project_id = ?
        """,
        projectId
    );
    int total = ((Number) row.get("total")).intValue();
    int due = row.get("due_count") == null ? 0 : ((Number) row.get("due_count")).intValue();
    return new WbMeasureAgg(total, due);
  }

  public WbMeasureAgg getWbSanctionMeasureAgg(UUID projectId) {
    Map<String, Object> row = jdbc.queryForMap(
        """
        select
          count(1) as total,
          sum(case when m.due_at is not null and m.due_at <= current_date then 1 else 0 end) as due_count
        from case_measure_sanction m
        join "case" c on c.id = m.case_id
        where c.project_id = ?
        """,
        projectId
    );
    int total = ((Number) row.get("total")).intValue();
    int due = row.get("due_count") == null ? 0 : ((Number) row.get("due_count")).intValue();
    return new WbMeasureAgg(total, due);
  }

  public record WbCostAgg(BigDecimal totalAmount, int itemCount) {
  }

  public WbCostAgg getWbCostAgg(UUID projectId) {
    Map<String, Object> row = jdbc.queryForMap(
        """
        select
          coalesce(sum(cc.amount), 0) as total_amount,
          count(1) as item_count
        from case_cost cc
        join "case" c on c.id = cc.case_id
        where c.project_id = ?
        """,
        projectId
    );
    return new WbCostAgg(
        (BigDecimal) row.get("total_amount"),
        ((Number) row.get("item_count")).intValue()
    );
  }

  public record WbCostCategoryAgg(String category, BigDecimal amount) {
  }

  public List<WbCostCategoryAgg> listWbCostAggByCategory(UUID projectId) {
    return jdbc.query(
        """
        select cc.category, coalesce(sum(cc.amount), 0) as amount
        from case_cost cc
        join "case" c on c.id = cc.case_id
        where c.project_id = ?
        group by cc.category
        order by amount desc, cc.category
        """,
        (rs, rn) -> new WbCostCategoryAgg(rs.getString("category"), rs.getBigDecimal("amount")),
        projectId
    );
  }

  public Map<String, Integer> listWbAttachmentCountsByObjectType(UUID projectId) {
    // Use UNION ALL to map attachment_link.object_id back to the project.
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        select t.object_type, sum(t.cnt) as cnt
          from (
            select al.object_type, count(1) as cnt
              from attachment_link al
             where al.object_type = 'project' and al.object_id = ?
             group by al.object_type

            union all
            select al.object_type, count(1) as cnt
              from attachment_link al
              join "case" c on c.id = al.object_id
             where al.object_type = 'case' and c.project_id = ?
             group by al.object_type

            union all
            select al.object_type, count(1) as cnt
              from attachment_link al
              join project_creditor pc on pc.id = al.object_id
             where al.object_type = 'creditor' and pc.project_id = ?
             group by al.object_type

            union all
            select al.object_type, count(1) as cnt
              from attachment_link al
              join project_debtor pd on pd.id = al.object_id
             where al.object_type = 'debtor' and pd.project_id = ?
             group by al.object_type

            union all
            select al.object_type, count(1) as cnt
              from attachment_link al
              join debtor_clue dc on dc.id = al.object_id
              join project_debtor pd on pd.id = dc.debtor_id
             where al.object_type = 'clue' and pd.project_id = ?
             group by al.object_type

            union all
            select al.object_type, count(1) as cnt
              from attachment_link al
              join case_procedure cp on cp.id = al.object_id
              join "case" c on c.id = cp.case_id
             where al.object_type = 'procedure' and c.project_id = ?
             group by al.object_type

            union all
            select al.object_type, count(1) as cnt
              from attachment_link al
              join case_measure_control m on m.id = al.object_id
              join "case" c on c.id = m.case_id
             where al.object_type = 'measure_control' and c.project_id = ?
             group by al.object_type

            union all
            select al.object_type, count(1) as cnt
              from attachment_link al
              join case_measure_sanction m on m.id = al.object_id
              join "case" c on c.id = m.case_id
             where al.object_type = 'measure_sanction' and c.project_id = ?
             group by al.object_type

            union all
            select al.object_type, count(1) as cnt
              from attachment_link al
              join case_cost cc on cc.id = al.object_id
              join "case" c on c.id = cc.case_id
             where al.object_type = 'cost' and c.project_id = ?
             group by al.object_type
          ) t
         group by t.object_type
         order by cnt desc, t.object_type
        """,
        projectId,
        projectId,
        projectId,
        projectId,
        projectId,
        projectId,
        projectId,
        projectId,
        projectId
    );

    Map<String, Integer> out = new LinkedHashMap<>();
    for (var r : rows) {
      String k = String.valueOf(r.get("object_type"));
      int v = ((Number) r.get("cnt")).intValue();
      out.put(k, v);
    }
    return out;
  }
}
