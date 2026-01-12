package com.secp.api.project;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.infra.RequestIdFilter;
import com.secp.api.infra.tx.TransactionalExecutor;
import com.secp.api.project.dto.ProjectDetailResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectDetailService {

  private final TransactionalExecutor tx;
  private final JdbcTemplate jdbc;
  private final ProjectDetailRepository repo;

  public ProjectDetailService(TransactionalExecutor tx, JdbcTemplate jdbc, ProjectDetailRepository repo) {
    this.tx = tx;
    this.jdbc = jdbc;
    this.repo = repo;
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
    ProjectDetailBundle bundle = tx.execute(principal, () -> {
      ProjectDetailBundle b = loadBundle(projectId);
      writeAudit(httpReq, principal.userId(), b.projectGroupId, "project_a4_export", "project", projectId,
          "{\"projectId\":\"" + projectId + "\"}");
      return b;
    });

    String html = renderHtml(bundle);
    return htmlToPdfBytes(html);
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

  private String renderHtml(ProjectDetailBundle b) {
    String template = readClasspathText("templates/project_a4.html");
    ProjectDetailResponse api = b.toApiResponse();

    String projectInfoRows = "";
    var p = api.project();
    projectInfoRows += tr("项目ID", String.valueOf(p.projectId()));
    projectInfoRows += tr("项目名称", p.name());
    projectInfoRows += tr("组ID", String.valueOf(p.groupId()));
    projectInfoRows += tr("状态", p.status());
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

    return template
        .replace("{{PROJECT_ID}}", escapeHtml(String.valueOf(b.projectId)))
        .replace("{{PROJECT_INFO_ROWS}}", projectInfoRows)
        .replace("{{PROJECT_MEMBERS_ROWS}}", projectMembersRows)
        .replace("{{CASE_MEMBERS_ROWS}}", caseMembersRows)
        .replace("{{CASES_ROWS}}", casesRows)
        .replace("{{TASKS_ROWS}}", tasksRows)
        .replace("{{INSTRUCTIONS_ROWS}}", instructionsRows)
        .replace("{{INSTRUCTION_OVERDUE_TOTAL}}", String.valueOf(b.totalInstructionOverdue()))
        .replace("{{PAYMENTS_ROWS}}", paymentsRows);
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
}
