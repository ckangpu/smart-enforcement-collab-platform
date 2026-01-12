package com.secp.api.project;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.infra.RequestIdFilter;
import com.secp.api.infra.tx.TransactionalExecutor;
import com.secp.api.project.dto.ProjectDetailResponse;
import com.secp.api.workbench.WorkbenchProjectRepository;
import com.secp.api.workbench.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectDetailService {

  private final TransactionalExecutor tx;
  private final JdbcTemplate jdbc;
  private final ProjectDetailRepository repo;
  private final WorkbenchProjectRepository wbRepo;

  public ProjectDetailService(TransactionalExecutor tx, JdbcTemplate jdbc, ProjectDetailRepository repo, WorkbenchProjectRepository wbRepo) {
    this.tx = tx;
    this.jdbc = jdbc;
    this.repo = repo;
    this.wbRepo = wbRepo;
  }

  public ProjectDetailResponse getDetail(AuthPrincipal principal, UUID projectId, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      ProjectDetailBundle bundle = loadBundle(projectId);
      writeAudit(httpReq, principal.userId(), bundle.projectGroupId, "project_detail_view", "project", projectId,
          "{\"projectId\":\"" + projectId + "\"}");
      return bundle.toApiResponse();
    });
  }

  public byte[] exportA4Pdf(AuthPrincipal principal, UUID projectId, HttpServletRequest httpReq) {
    ExportBundle bundle = tx.execute(principal, () -> {
      ProjectDetailBundle b = loadBundle(projectId);
      WorkbenchA4Bundle wb = loadWorkbenchA4(projectId);
      writeAudit(httpReq, principal.userId(), b.projectGroupId, "project_a4_export", "project", projectId,
          "{\"projectId\":\"" + projectId + "\"}");
      return new ExportBundle(b, wb);
    });

    String html = renderHtml(bundle.legacy(), bundle.workbench());
    return htmlToPdfBytes(html);
  }

  private record ExportBundle(ProjectDetailBundle legacy, WorkbenchA4Bundle workbench) {
  }

  private ProjectDetailBundle loadBundle(UUID projectId) {
    Map<String, Object> p = repo.findProject(projectId).orElseThrow(ProjectNotFoundException::new);
    UUID groupId = (UUID) p.get("group_id");

    List<Map<String, Object>> cases = repo.listCases(projectId);
    List<Map<String, Object>> tasks = repo.listTasks(projectId);
    List<Map<String, Object>> projectMembers = repo.listProjectMembers(projectId);
    List<Map<String, Object>> caseMembers = repo.listCaseMembers(projectId);
    List<ProjectDetailRepository.InstructionAggRow> instructions = repo.listInstructionAgg(projectId);
    ProjectDetailRepository.PaymentsAggRow payments = repo.getPaymentsAgg(projectId);

    return new ProjectDetailBundle(projectId, groupId, p, cases, tasks, projectMembers, caseMembers, instructions, payments);
  }

  private record ProjectDetailBundle(
      UUID projectId,
      UUID projectGroupId,
      Map<String, Object> projectRow,
      List<Map<String, Object>> caseRows,
      List<Map<String, Object>> taskRows,
      List<Map<String, Object>> projectMemberRows,
      List<Map<String, Object>> caseMemberRows,
      List<ProjectDetailRepository.InstructionAggRow> instructionAgg,
      ProjectDetailRepository.PaymentsAggRow paymentsAgg
  ) {

    ProjectDetailResponse toApiResponse() {
      ProjectDetailResponse.ProjectDto projectDto = new ProjectDetailResponse.ProjectDto(
          (UUID) projectRow.get("id"),
          String.valueOf(projectRow.get("code")),
          toLocalDate(projectRow.get("accepted_at")),
          String.valueOf(projectRow.get("name")),
          (UUID) projectRow.get("group_id"),
          toBizTags(projectRow.get("biz_tags")),
          (BigDecimal) projectRow.get("mandate_amount"),
          (BigDecimal) projectRow.get("execution_target_amount"),
          String.valueOf(projectRow.get("status")),
          toOffsetDateTime(projectRow.get("created_at")),
          toOffsetDateTime(projectRow.get("updated_at"))
      );

      List<ProjectDetailResponse.ProjectMemberDto> pms = new ArrayList<>();
      for (Map<String, Object> r : projectMemberRows) {
        pms.add(new ProjectDetailResponse.ProjectMemberDto(
            (UUID) r.get("user_id"),
            String.valueOf(r.get("member_role")),
            String.valueOf(r.get("username")),
            maskPhone(String.valueOf(r.get("phone")))
        ));
      }

      List<ProjectDetailResponse.CaseMemberDto> cms = new ArrayList<>();
      for (Map<String, Object> r : caseMemberRows) {
        cms.add(new ProjectDetailResponse.CaseMemberDto(
            (UUID) r.get("case_id"),
            (UUID) r.get("user_id"),
            String.valueOf(r.get("member_role")),
            String.valueOf(r.get("username")),
            maskPhone(String.valueOf(r.get("phone")))
        ));
      }

      ProjectDetailResponse.MembersDto membersDto = new ProjectDetailResponse.MembersDto(pms, cms);

      List<ProjectDetailResponse.CaseDto> cs = new ArrayList<>();
      for (Map<String, Object> r : caseRows) {
        cs.add(new ProjectDetailResponse.CaseDto(
            (UUID) r.get("id"),
            String.valueOf(r.get("code")),
            toLocalDate(r.get("accepted_at")),
            String.valueOf(r.get("title")),
            String.valueOf(r.get("status")),
            toOffsetDateTime(r.get("created_at"))
        ));
      }

      List<ProjectDetailResponse.TaskDto> ts = new ArrayList<>();
      for (Map<String, Object> r : taskRows) {
        ts.add(new ProjectDetailResponse.TaskDto(
            (UUID) r.get("id"),
            String.valueOf(r.get("title")),
            String.valueOf(r.get("status")),
            r.get("priority") == null ? null : String.valueOf(r.get("priority")),
            (UUID) r.get("case_id"),
            (UUID) r.get("instruction_item_id"),
            toOffsetDateTime(r.get("plan_end")),
            (UUID) r.get("assignee_user_id")
        ));
      }

      List<ProjectDetailResponse.InstructionSummaryDto> ins = new ArrayList<>();
      for (ProjectDetailRepository.InstructionAggRow r : instructionAgg) {
        ins.add(new ProjectDetailResponse.InstructionSummaryDto(
            r.instructionId(),
            r.title(),
            r.status(),
            r.version(),
            r.deadline(),
            r.issuedAt(),
            r.itemTotal(),
            r.itemDone()
        ));
      }

      ProjectDetailResponse.PaymentsSummaryDto pay = new ProjectDetailResponse.PaymentsSummaryDto(
          paymentsAgg.sumAll(),
          paymentsAgg.sum30d(),
          paymentsAgg.effectiveCount(),
          paymentsAgg.latestPaidAt()
      );

      return new ProjectDetailResponse(projectDto, membersDto, cs, ts, ins, pay);
    }

    int totalInstructionOverdue() {
      int n = 0;
      for (ProjectDetailRepository.InstructionAggRow r : instructionAgg) {
        n += r.itemOverdue();
      }
      return n;
    }
  }

  private record WorkbenchA4Bundle(
      WorkbenchProjectBaseDto project,
      List<WorkbenchCreditorDto> creditors,
      List<WorkbenchDebtorDto> debtors,
      List<WorkbenchCaseDto> cases,
      List<ClueRow> clues,
      List<ItemRow> items,
      List<AttachmentRow> attachments
  ) {
  }

  private record ClueRow(
      UUID debtorId,
      String debtorCode,
      String debtorName,
      DebtorClueDto clue
  ) {
  }

  private record ItemRow(
      String type,
      UUID caseId,
      String caseCode,
      String caseTitle,
      String name,
      String date,
      String amount,
      String result,
      String note,
      UUID objectId,
      String objectType
  ) {
  }

  private record AttachmentRow(
      String objectType,
      UUID objectId,
      UUID attachmentId,
      UUID fileId,
      String title,
      String filename,
      String contentType,
      long sizeBytes,
      Instant createdAt
  ) {
  }

  private WorkbenchA4Bundle loadWorkbenchA4(UUID projectId) {
    // All reads are inside tx.execute(principal, ...), so RLS is applied.
    WorkbenchProjectBaseDto project = wbRepo.findProjectDetail(projectId).orElse(null);
    List<WorkbenchCreditorDto> creditors = wbRepo.listCreditors(projectId);
    List<WorkbenchDebtorDto> debtors = wbRepo.listDebtors(projectId);
    List<WorkbenchCaseDto> cases = wbRepo.listCases(projectId);

    Map<UUID, WorkbenchDebtorDto> debtorById = new HashMap<>();
    for (var d : debtors) debtorById.put(d.debtorId(), d);

    List<ClueRow> clueRows = new ArrayList<>();
    for (var d : debtors) {
      List<DebtorClueDto> clues = wbRepo.listClues(d.debtorId());
      for (var c : clues) {
        clueRows.add(new ClueRow(d.debtorId(), d.brCode(), d.name(), c));
      }
    }

    List<ItemRow> itemRows = new ArrayList<>();
    for (var c : cases) {
      String caseCode = c.caseCode();
      String caseTitle = c.name();

      for (var p : wbRepo.listProcedures(c.caseId())) {
        itemRows.add(new ItemRow(
            "程序",
            c.caseId(),
            caseCode,
            caseTitle,
            p.name(),
            p.decidedAt() == null ? "" : p.decidedAt().toString(),
            "",
            "",
            "",
            p.procedureId(),
            "case_procedure"
        ));
      }

      for (var m : wbRepo.listControlMeasures(c.caseId())) {
        itemRows.add(new ItemRow(
            "控制措施",
            c.caseId(),
            caseCode,
            caseTitle,
            m.name(),
            m.dueAt() == null ? "" : m.dueAt().toString(),
            "",
            m.result() == null ? "" : m.result(),
            m.note() == null ? "" : m.note(),
            m.measureId(),
            "case_measure_control"
        ));
      }

      for (var m : wbRepo.listSanctionMeasures(c.caseId())) {
        itemRows.add(new ItemRow(
            "惩戒措施",
            c.caseId(),
            caseCode,
            caseTitle,
            m.name(),
            m.dueAt() == null ? "" : m.dueAt().toString(),
            "",
            m.result() == null ? "" : m.result(),
            m.note() == null ? "" : m.note(),
            m.measureId(),
            "case_measure_sanction"
        ));
      }

      for (var cost : wbRepo.listCosts(c.caseId())) {
        itemRows.add(new ItemRow(
            "费用",
            c.caseId(),
            caseCode,
            caseTitle,
            cost.category(),
            cost.occurredAt() == null ? "" : cost.occurredAt().toString(),
            cost.amount() == null ? "" : cost.amount().toPlainString(),
            "",
            cost.note() == null ? "" : cost.note(),
            cost.costId(),
            "case_cost"
        ));
      }
    }

    // Attachments: best-effort summary by object (capped to keep PDF manageable)
    List<AttachmentRow> attachmentRows = new ArrayList<>();
    addAttachments(attachmentRows, "project", projectId);
    for (var c : cases) addAttachments(attachmentRows, "case", c.caseId());
    for (var c : creditors) addAttachments(attachmentRows, "project_creditor", c.creditorId());
    for (var d : debtors) addAttachments(attachmentRows, "project_debtor", d.debtorId());
    for (var cr : clueRows) addAttachments(attachmentRows, "debtor_clue", cr.clue().clueId());
    for (var it : itemRows) {
      if (it.objectId() != null && it.objectType() != null) {
        addAttachments(attachmentRows, it.objectType(), it.objectId());
      }
    }

    attachmentRows.sort(Comparator.comparing(AttachmentRow::createdAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
    if (attachmentRows.size() > 200) {
      attachmentRows = new ArrayList<>(attachmentRows.subList(0, 200));
    }

    return new WorkbenchA4Bundle(project, creditors, debtors, cases, clueRows, itemRows, attachmentRows);
  }

  private void addAttachments(List<AttachmentRow> out, String objectType, UUID objectId) {
    List<AttachmentLinkDto> list = wbRepo.listAttachments(objectType, objectId);
    for (var a : list) {
      out.add(new AttachmentRow(
          a.objectType(),
          a.objectId(),
          a.attachmentId(),
          a.fileId(),
          a.title(),
          a.filename(),
          a.contentType(),
          a.sizeBytes(),
          a.createdAt()
      ));
    }
  }

  private String renderHtml(ProjectDetailBundle b, WorkbenchA4Bundle wb) {
    String template = readClasspathText("templates/project_a4.html");
    ProjectDetailResponse api = b.toApiResponse();

    String projectInfoRows = "";
    var p = api.project();
    projectInfoRows += tr("项目ID", String.valueOf(p.projectId()));
    projectInfoRows += tr("项目名称", p.name());
    projectInfoRows += tr("组ID", String.valueOf(p.groupId()));
    projectInfoRows += tr("状态", p.status());
    if (wb != null && wb.project != null) {
      projectInfoRows += tr("委托人", wb.project.entrustor());
      projectInfoRows += tr("进度状态", wb.project.progressStatus());
      projectInfoRows += tr("目标日期", wb.project.targetDate() == null ? "" : wb.project.targetDate().toString());
      projectInfoRows += tr("负责人", wb.project.ownerName());
      projectInfoRows += tr("主办", wb.project.leadName());
      projectInfoRows += tr("协办", wb.project.assistName());
      projectInfoRows += tr("备注", wb.project.note());
    }
    projectInfoRows += tr("业务标签", escapeHtml(String.join(", ", p.bizTags() == null ? List.of() : p.bizTags())));
    projectInfoRows += tr("委托金额", p.mandateAmount() == null ? "" : p.mandateAmount().toPlainString());
    projectInfoRows += tr("执行目标金额", p.executionTargetAmount() == null ? "" : p.executionTargetAmount().toPlainString());
    projectInfoRows += tr("创建时间", p.createdAt() == null ? "" : p.createdAt().toString());
    projectInfoRows += tr("更新时间", p.updatedAt() == null ? "" : p.updatedAt().toString());

    String projectMembersRows = "";
    for (var m : api.members().projectMembers()) {
      projectMembersRows += "<tr>" + td(m.userId()) + td(m.role()) + td(m.username()) + td(m.phoneMasked()) + "</tr>";
    }

    String caseMembersRows = "";
    for (var m : api.members().caseMembers()) {
      caseMembersRows += "<tr>" + td(m.caseId()) + td(m.userId()) + td(m.role()) + td(m.username()) + td(m.phoneMasked()) + "</tr>";
    }

    String casesRows = "";
    for (var c : api.cases()) {
      casesRows += "<tr>" + td(c.caseId()) + td(c.title()) + td(c.status()) + td(c.createdAt()) + "</tr>";
    }

    String tasksRows = "";
    for (var t : api.tasks()) {
      tasksRows += "<tr>" + td(t.taskId()) + td(t.title()) + td(t.status()) + td(t.priority()) + td(t.caseId()) + td(t.instructionItemId()) + td(t.dueAt()) + td(t.assigneeUserId()) + "</tr>";
    }

    String instructionsRows = "";
    for (var i : api.instructions()) {
      int overdue = 0;
      for (var r : b.instructionAgg) {
        if (r.instructionId().equals(i.instructionId())) {
          overdue = r.itemOverdue();
          break;
        }
      }
      instructionsRows += "<tr>" + td(i.instructionId()) + td(i.title()) + td(i.status()) + td(i.version()) + td(i.deadline()) + td(i.issuedAt()) + td(i.itemTotal()) + td(i.itemDone()) + td(overdue) + "</tr>";
    }

    String paymentsRows = "";
    paymentsRows += tr("累计回款（有效）", api.payments().sumAll() == null ? "0" : api.payments().sumAll().toPlainString());
    paymentsRows += tr("近30天回款（有效）", api.payments().sum30d() == null ? "0" : api.payments().sum30d().toPlainString());
    paymentsRows += tr("有效笔数", String.valueOf(api.payments().effectiveCount()));
    paymentsRows += tr("最近回款时间", api.payments().latestPaidAt() == null ? "" : api.payments().latestPaidAt().toString());

    String wbSrSummary = wb == null ? "" : buildWbSrSummary(b.projectId);

    String wbCreditorsRows = "";
    if (wb != null && wb.creditors != null) {
      for (var c : wb.creditors) {
        wbCreditorsRows += "<tr>" + td(c.creditorId()) + td(c.srCode()) + td(c.name()) + td(c.idNo()) + td(c.unifiedCode()) + td(c.eDeliveryPhone()) + "</tr>";
      }
    }
    if (wbCreditorsRows.isBlank()) {
      wbCreditorsRows = emptyRow(6);
    }

    String wbBrSummary = wb == null ? "" : buildWbBrSummary(b.projectId);

    String wbDebtorsRows = "";
    if (wb != null && wb.debtors != null) {
      for (var d : wb.debtors) {
        wbDebtorsRows += "<tr>" + td(d.debtorId()) + td(d.brCode()) + td(d.name()) + td(d.idNo()) + td(d.unifiedCode()) + td(d.eDeliveryPhone()) + "</tr>";
      }
    }
    if (wbDebtorsRows.isBlank()) {
      wbDebtorsRows = emptyRow(6);
    }

    String wbCaseExecutionBasisSummary = wb == null ? "" : buildWbCaseExecutionBasisSummary(b.projectId);
    String wbCaseBasisRows = "";
    if (wb != null && wb.cases != null) {
      for (var c : wb.cases) {
        String creditor = (c.creditorCode() == null ? "" : c.creditorCode()) + (c.creditorName() == null ? "" : (" " + c.creditorName()));
        String debtor = (c.debtorCode() == null ? "" : c.debtorCode()) + (c.debtorName() == null ? "" : (" " + c.debtorName()));
        wbCaseBasisRows += "<tr>" + td(c.caseId()) + td(c.caseCode()) + td(c.name()) + td(creditor.trim()) + td(debtor.trim())
            + td(c.cause()) + td(c.basisDocType()) + td(c.basisDocNo()) + td(c.basisOrg()) + td(c.basisMainText()) + "</tr>";
      }
    }
    if (wbCaseBasisRows.isBlank()) {
      wbCaseBasisRows = emptyRow(10);
    }

    String wbCluesSummary = wb == null ? "" : buildWbCluesSummary(b.projectId);
    String wbCluesRows = "";
    if (wb != null && wb.clues != null) {
      for (var r : wb.clues) {
        var x = r.clue;
        String debtor = (r.debtorCode() == null ? "" : r.debtorCode()) + (r.debtorName() == null ? "" : (" " + r.debtorName()));
        wbCluesRows += "<tr>" + td(x.clueId()) + td(x.xsCode()) + td(debtor.trim()) + td(x.category()) + td(x.detail()) + td(x.source())
            + td(x.collectedAt() == null ? "" : x.collectedAt().toString()) + td(x.collectorName()) + "</tr>";
      }
    }
    if (wbCluesRows.isBlank()) {
      wbCluesRows = emptyRow(8);
    }

    String wbProceduresSummary = wb == null ? "" : buildWbProceduresSummary(b.projectId);
    String wbControlMeasuresSummary = wb == null ? "" : buildWbMeasuresControlSummary(b.projectId);
    String wbSanctionMeasuresSummary = wb == null ? "" : buildWbMeasuresSanctionSummary(b.projectId);
    String wbCostsSummary = wb == null ? "" : buildWbCostsSummary(b.projectId);

    String wbItemsRows = "";
    if (wb != null && wb.items != null) {
      for (var it : wb.items) {
        wbItemsRows += "<tr>" + td(it.type()) + td(it.caseId()) + td(it.caseCode()) + td(it.caseTitle()) + td(it.name()) + td(it.date())
            + td(it.amount()) + td(it.result()) + td(it.note()) + "</tr>";
      }
    }
    if (wbItemsRows.isBlank()) {
      wbItemsRows = emptyRow(9);
    }

    String wbAttachmentsRows = "";
    int wbAttachmentTotal = 0;
    if (wb != null && wb.attachments != null) {
      wbAttachmentTotal = wb.attachments.size();
      for (var a : wb.attachments) {
        wbAttachmentsRows += "<tr>" + td(a.objectType()) + td(a.objectId()) + td(a.title()) + td(a.fileId()) + td(a.filename()) + td(a.contentType())
            + td(a.sizeBytes()) + td(a.createdAt() == null ? "" : a.createdAt().toString()) + "</tr>";
      }
    }
    if (wbAttachmentsRows.isBlank()) {
      wbAttachmentsRows = emptyRow(8);
    }

    String wbAttachmentsSummary = wb == null ? "" : buildWbAttachmentsSummary(b.projectId, wbAttachmentTotal);

    String out = template
        .replace("{{PROJECT_ID}}", escapeHtml(String.valueOf(b.projectId)))
        .replace("{{PROJECT_INFO_ROWS}}", projectInfoRows)
        .replace("{{PROJECT_MEMBERS_ROWS}}", projectMembersRows)
        .replace("{{CASE_MEMBERS_ROWS}}", caseMembersRows)
        .replace("{{CASES_ROWS}}", casesRows)
        .replace("{{TASKS_ROWS}}", tasksRows)
        .replace("{{INSTRUCTIONS_ROWS}}", instructionsRows)
        .replace("{{INSTRUCTION_OVERDUE_TOTAL}}", String.valueOf(b.totalInstructionOverdue()))
        .replace("{{PAYMENTS_ROWS}}", paymentsRows)
        .replace("{{WB_SR}}", escapeHtml(orEmptyState(wbSrSummary)))
        .replace("{{WB_BR}}", escapeHtml(orEmptyState(wbBrSummary)))
        .replace("{{WB_CASE_EXECUTION_BASIS}}", escapeHtml(orEmptyState(wbCaseExecutionBasisSummary)))
        .replace("{{WB_CLUES}}", escapeHtml(orEmptyState(wbCluesSummary)))
        .replace("{{WB_PROCEDURES}}", escapeHtml(orEmptyState(wbProceduresSummary)))
        .replace("{{WB_MEASURES_CONTROL}}", escapeHtml(orEmptyState(wbControlMeasuresSummary)))
        .replace("{{WB_MEASURES_SANCTION}}", escapeHtml(orEmptyState(wbSanctionMeasuresSummary)))
        .replace("{{WB_COSTS}}", escapeHtml(orEmptyState(wbCostsSummary)))
        .replace("{{WB_ATTACHMENTS}}", escapeHtml(orEmptyState(wbAttachmentsSummary)))
        .replace("{{WB_CREDITORS_ROWS}}", wbCreditorsRows)
        .replace("{{WB_DEBTORS_ROWS}}", wbDebtorsRows)
        .replace("{{WB_CASE_BASIS_ROWS}}", wbCaseBasisRows)
        .replace("{{WB_CLUES_ROWS}}", wbCluesRows)
        .replace("{{WB_ITEMS_ROWS}}", wbItemsRows)
        .replace("{{WB_ATTACHMENTS_TOTAL}}", String.valueOf(wbAttachmentTotal))
        .replace("{{WB_ATTACHMENTS_ROWS}}", wbAttachmentsRows);

    // Safety net: never leave template variables unreplaced (prevents PDF render oddities).
    return out.replaceAll("\\\\{\\\\{[A-Z0-9_]+}}", escapeHtml("暂无数据"));
  }

  private static String emptyRow(int colSpan) {
    return "<tr><td colspan=\"" + colSpan + "\">" + escapeHtml("暂无数据") + "</td></tr>";
  }

  private static String orEmptyState(String s) {
    if (s == null) return "暂无数据";
    if (s.isBlank()) return "暂无数据";
    return s;
  }

  private String buildWbSrSummary(UUID projectId) {
    int total = repo.countWbCreditors(projectId);
    if (total <= 0) return "暂无数据";
    List<String> names = repo.listWbCreditorNames(projectId, 3);
    String sample = names.isEmpty() ? "" : ("，示例：" + String.join("、", names));
    return "共" + total + "个" + sample;
  }

  private String buildWbBrSummary(UUID projectId) {
    int total = repo.countWbDebtors(projectId);
    if (total <= 0) return "暂无数据";
    List<String> names = repo.listWbDebtorNames(projectId, 3);
    String sample = names.isEmpty() ? "" : ("，示例：" + String.join("、", names));
    return "共" + total + "个" + sample;
  }

  private String buildWbCaseExecutionBasisSummary(UUID projectId) {
    var agg = repo.getWbCaseBasisAgg(projectId);
    if (agg.caseTotal() <= 0) return "暂无数据";
    return "案件总数" + agg.caseTotal() + "，已填写执行依据" + agg.basisFilled() + "。";
  }

  private String buildWbCluesSummary(UUID projectId) {
    int total = repo.countWbClues(projectId);
    if (total <= 0) return "暂无数据";

    String byCat = repo.listWbClueCategoryCounts(projectId).stream()
        .limit(6)
        .map(x -> x.category() + "(" + x.cnt() + ")")
        .collect(Collectors.joining("、"));

    String top = repo.listWbClueTop(projectId, 3).stream()
        .map(x -> {
          String d = x.detail();
          if (d == null) d = "";
          d = d.replace("\n", " ").replace("\r", " ").trim();
          if (d.length() > 30) d = d.substring(0, 30) + "…";
          String who = x.debtorName() == null ? "" : ("（" + x.debtorName() + "）");
          return x.category() + who + ":" + d;
        })
        .collect(Collectors.joining("；"));

    String topPart = top.isBlank() ? "" : ("；最近：" + top);
    String catPart = byCat.isBlank() ? "" : ("；分类：" + byCat);
    return "线索总数" + total + catPart + topPart;
  }

  private String buildWbProceduresSummary(UUID projectId) {
    var rows = repo.listWbProcedureAggByCase(projectId);
    if (rows.isEmpty()) return "暂无数据";
    int total = rows.stream().mapToInt(ProjectDetailRepository.WbCaseProcedureAgg::procedureCount).sum();
    String byCase = rows.stream()
        .limit(6)
        .map(r -> {
          String code = r.caseCode() == null ? "" : r.caseCode();
          String title = r.caseTitle() == null ? "" : r.caseTitle();
          String latest = r.latestDecidedAt() == null ? "" : ("，最近裁定日期：" + r.latestDecidedAt());
          return code + " " + title + "：" + r.procedureCount() + "条" + latest;
        })
        .collect(Collectors.joining("；"));
    return "总条数" + total + "；按案件：" + byCase;
  }

  private String buildWbMeasuresControlSummary(UUID projectId) {
    var agg = repo.getWbControlMeasureAgg(projectId);
    if (agg.total() <= 0) return "暂无数据";
    return "总条数" + agg.total() + "，到期条数" + agg.dueCount() + "。";
  }

  private String buildWbMeasuresSanctionSummary(UUID projectId) {
    var agg = repo.getWbSanctionMeasureAgg(projectId);
    if (agg.total() <= 0) return "暂无数据";
    return "总条数" + agg.total() + "，到期条数" + agg.dueCount() + "。";
  }

  private String buildWbCostsSummary(UUID projectId) {
    var agg = repo.getWbCostAgg(projectId);
    if (agg.itemCount() <= 0) return "暂无数据";
    String byCat = repo.listWbCostAggByCategory(projectId).stream()
        .limit(6)
        .map(x -> x.category() + "：" + (x.amount() == null ? "0" : x.amount().toPlainString()))
        .collect(Collectors.joining("；"));
    String catPart = byCat.isBlank() ? "" : ("；分类：" + byCat);
    return "总额" + (agg.totalAmount() == null ? "0" : agg.totalAmount().toPlainString()) + "，明细条数" + agg.itemCount() + catPart;
  }

  private String buildWbAttachmentsSummary(UUID projectId, int listShownTotal) {
    if (listShownTotal <= 0) {
      // Still try to reflect counts from DB; if also empty, return empty-state.
      var cnts = repo.listWbAttachmentCountsByObjectType(projectId);
      int total = cnts.values().stream().mapToInt(Integer::intValue).sum();
      if (total <= 0) return "暂无数据";
      String byType = cnts.entrySet().stream()
          .limit(8)
          .map(e -> e.getKey() + "(" + e.getValue() + ")")
          .collect(Collectors.joining("、"));
      return "总条数" + total + "；按对象类型：" + byType + "。";
    }

    var cnts = repo.listWbAttachmentCountsByObjectType(projectId);
    int total = cnts.values().stream().mapToInt(Integer::intValue).sum();
    String byType = cnts.isEmpty() ? "" : cnts.entrySet().stream()
        .limit(8)
        .map(e -> e.getKey() + "(" + e.getValue() + ")")
        .collect(Collectors.joining("、"));
    String typePart = byType.isBlank() ? "" : ("；按对象类型：" + byType);
    String shown = "；下表最多展示 200 条";
    return "总条数" + total + typePart + shown;
  }

  private byte[] htmlToPdfBytes(String html) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      PdfRendererBuilder builder = new PdfRendererBuilder();
      builder.useFastMode();
      builder.withHtmlContent(html, null);
      builder.toStream(out);
      builder.run();
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("PDF_RENDER_FAILED", e);
    }
  }

  private String readClasspathText(String path) {
    try {
      ClassPathResource r = new ClassPathResource(path);
      byte[] bytes = r.getInputStream().readAllBytes();
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("TEMPLATE_NOT_FOUND: " + path, e);
    }
  }

  private static String maskPhone(String phone) {
    if (phone == null) return "";
    String p = phone.trim();
    if (p.length() <= 4) return "****" + p;
    String last4 = p.substring(p.length() - 4);
    if (p.length() >= 7) {
      return p.substring(0, 3) + "****" + last4;
    }
    return "****" + last4;
  }

  private static String escapeHtml(String s) {
    if (s == null) return "";
    return s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static String td(Object v) {
    return "<td>" + escapeHtml(v == null ? "" : String.valueOf(v)) + "</td>";
  }

  private static String tr(String k, String v) {
    return "<tr><th class=\"k\">" + escapeHtml(k) + "</th><td>" + escapeHtml(v) + "</td></tr>";
  }

  private void writeAudit(HttpServletRequest req,
                          UUID actorUserId,
                          UUID groupId,
                          String action,
                          String objectType,
                          UUID objectId,
                          String summaryJson) {
    String rid = (String) req.getAttribute(RequestIdFilter.REQ_ID_ATTR);
    jdbc.update(
        """
        insert into audit_log(group_id, actor_user_id, action, object_type, object_id, request_id, ip, user_agent, summary)
        values (?,?,?,?,?,?,?,?, ?::jsonb)
        """,
        groupId,
        actorUserId,
        action,
        objectType,
        objectId,
        rid,
        req.getRemoteAddr(),
        req.getHeader("User-Agent"),
        summaryJson
    );
  }

  private static List<String> toBizTags(Object o) {
    if (o == null) return List.of();
    try {
      if (o instanceof String[] sa) {
        return List.of(sa);
      }
      if (o instanceof java.sql.Array a) {
        Object arr = a.getArray();
        if (arr instanceof String[] sa) return List.of(sa);
      }
    } catch (Exception ignored) {
    }
    return List.of();
  }

  private static OffsetDateTime toOffsetDateTime(Object v) {
    if (v == null) return null;
    if (v instanceof OffsetDateTime odt) return odt;
    if (v instanceof Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
    if (v instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC);
    return OffsetDateTime.parse(String.valueOf(v));
  }

  private static LocalDate toLocalDate(Object v) {
    if (v == null) return null;
    if (v instanceof LocalDate ld) return ld;
    if (v instanceof java.sql.Date d) return d.toLocalDate();
    if (v instanceof Timestamp ts) return ts.toLocalDateTime().toLocalDate();
    if (v instanceof LocalDateTime ldt) return ldt.toLocalDate();
    return LocalDate.parse(String.valueOf(v));
  }
}
