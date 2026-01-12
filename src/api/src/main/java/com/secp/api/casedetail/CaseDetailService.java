package com.secp.api.casedetail;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.infra.RequestIdFilter;
import com.secp.api.infra.tx.TransactionalExecutor;
import com.secp.api.casedetail.dto.CaseDetailResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CaseDetailService {

  private final TransactionalExecutor tx;
  private final JdbcTemplate jdbc;
  private final CaseDetailRepository repo;

  public CaseDetailService(TransactionalExecutor tx, JdbcTemplate jdbc, CaseDetailRepository repo) {
    this.tx = tx;
    this.jdbc = jdbc;
    this.repo = repo;
  }

  public CaseDetailResponse getDetail(AuthPrincipal principal, UUID caseId, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      CaseDetailBundle b = loadBundle(caseId);
      writeAudit(httpReq, principal.userId(), b.groupId, "case_detail_view", "case", caseId,
          "{\"caseId\":\"" + caseId + "\"}");
      return b.toApiResponse();
    });
  }

  public record PdfResult(String code, byte[] bytes) {
  }

  public PdfResult exportA4Pdf(AuthPrincipal principal, UUID caseId, HttpServletRequest httpReq) {
    CaseDetailBundle b = tx.execute(principal, () -> {
      CaseDetailBundle bundle = loadBundle(caseId);
      writeAudit(httpReq, principal.userId(), bundle.groupId, "case_a4_export", "case", caseId,
          "{\"caseId\":\"" + caseId + "\",\"code\":\"" + bundle.caseCode() + "\"}");
      return bundle;
    });

    String html = renderHtml(b);
    return new PdfResult(b.caseCode(), htmlToPdfBytes(html));
  }

  private CaseDetailBundle loadBundle(UUID caseId) {
    Map<String, Object> row = repo.findCaseWithProject(caseId).orElseThrow(CaseNotFoundException::new);

    UUID groupId = (UUID) row.get("case_group_id");

    List<Map<String, Object>> members = repo.listMembers(caseId);
    List<Map<String, Object>> tasks = repo.listTasks(caseId);
    List<CaseDetailRepository.InstructionAggRow> instructions = repo.listInstructionAgg(caseId);
    CaseDetailRepository.PaymentsAggRow payments = repo.getPaymentsAgg(caseId);

    return new CaseDetailBundle(caseId, groupId, row, members, tasks, instructions, payments);
  }

  private record CaseDetailBundle(
      UUID caseId,
      UUID groupId,
      Map<String, Object> row,
      List<Map<String, Object>> memberRows,
      List<Map<String, Object>> taskRows,
      List<CaseDetailRepository.InstructionAggRow> instructionRows,
      CaseDetailRepository.PaymentsAggRow paymentsAgg
  ) {

    String caseCode() {
      return String.valueOf(row.get("case_code"));
    }

    String caseTitle() {
      return String.valueOf(row.get("case_title"));
    }

    String projectName() {
      return String.valueOf(row.get("project_name"));
    }

    String projectCode() {
      return String.valueOf(row.get("project_code"));
    }

    CaseDetailResponse toApiResponse() {
      CaseDetailResponse.CaseDto caze = new CaseDetailResponse.CaseDto(
          (UUID) row.get("case_id"),
          String.valueOf(row.get("case_code")),
            toLocalDate(row.get("case_accepted_at")),
          String.valueOf(row.get("case_title")),
          String.valueOf(row.get("case_status")),
          (UUID) row.get("case_group_id"),
          (UUID) row.get("project_id"),
          toOffsetDateTime(row.get("case_created_at")),
          toOffsetDateTime(row.get("case_updated_at"))
      );

      CaseDetailResponse.ProjectDto project = new CaseDetailResponse.ProjectDto(
          (UUID) row.get("project_id"),
          String.valueOf(row.get("project_code")),
          String.valueOf(row.get("project_name"))
      );

      List<CaseDetailResponse.MemberDto> members = new ArrayList<>();
      for (Map<String, Object> r : memberRows) {
        members.add(new CaseDetailResponse.MemberDto(
            (UUID) r.get("user_id"),
            String.valueOf(r.get("member_role")),
            String.valueOf(r.get("username")),
            maskPhone(String.valueOf(r.get("phone")))
        ));
      }

      List<CaseDetailResponse.TaskDto> tasks = new ArrayList<>();
      for (Map<String, Object> r : taskRows) {
        tasks.add(new CaseDetailResponse.TaskDto(
            (UUID) r.get("id"),
            String.valueOf(r.get("title")),
            String.valueOf(r.get("status")),
            String.valueOf(r.get("priority")),
            toOffsetDateTime(r.get("plan_end")),
            (UUID) r.get("assignee_user_id")
        ));
      }

      List<CaseDetailResponse.InstructionDto> ins = new ArrayList<>();
      for (CaseDetailRepository.InstructionAggRow r : instructionRows) {
        ins.add(new CaseDetailResponse.InstructionDto(
            r.instructionId(),
            r.title(),
            r.status(),
            r.version(),
            r.deadline(),
            r.issuedAt(),
            r.itemTotal(),
            r.itemDone(),
            r.itemOverdue()
        ));
      }

      CaseDetailResponse.PaymentsSummaryDto pay = new CaseDetailResponse.PaymentsSummaryDto(
          paymentsAgg.sumAll(),
          paymentsAgg.sum30d(),
          paymentsAgg.effectiveCount(),
          paymentsAgg.latestPaidAt()
      );

      return new CaseDetailResponse(caze, project, members, tasks, ins, pay);
    }
  }

  private String renderHtml(CaseDetailBundle b) {
    String template = readClasspathText("templates/case_a4.html");

    String infoRows = "";
    infoRows += tr("案件编号", b.caseCode());
    infoRows += tr("案件ID", b.caseId.toString());
    infoRows += tr("案件标题", b.caseTitle());
    infoRows += tr("所属项目", b.projectCode() + " " + b.projectName());

    String membersRows = "";
    for (Map<String, Object> r : b.memberRows) {
      membersRows += tr4(
          String.valueOf(r.get("user_id")),
          String.valueOf(r.get("member_role")),
          String.valueOf(r.get("username")),
          maskPhone(String.valueOf(r.get("phone")))
      );
    }

    String tasksRows = "";
    for (Map<String, Object> r : b.taskRows) {
      tasksRows += tr6(
          String.valueOf(r.get("id")),
          String.valueOf(r.get("title")),
          String.valueOf(r.get("status")),
          String.valueOf(r.get("priority")),
          String.valueOf(r.get("assignee_user_id")),
          fmtDateTime(r.get("plan_end"))
      );
    }

    String insRows = "";
    int overdueTotal = 0;
    for (CaseDetailRepository.InstructionAggRow r : b.instructionRows) {
      overdueTotal += r.itemOverdue();
      insRows += tr9(
          String.valueOf(r.instructionId()),
          String.valueOf(r.title()),
          String.valueOf(r.status()),
          String.valueOf(r.version()),
          fmtDateTime(r.deadline()),
          fmtDateTime(r.issuedAt()),
          String.valueOf(r.itemTotal()),
          String.valueOf(r.itemDone()),
          String.valueOf(r.itemOverdue())
      );
    }

    String paymentsRows = "";
    paymentsRows += tr("合计金额", fmtMoney(b.paymentsAgg.sumAll()));
    paymentsRows += tr("近30天金额", fmtMoney(b.paymentsAgg.sum30d()));
    paymentsRows += tr("有效回款笔数", String.valueOf(b.paymentsAgg.effectiveCount()));
    paymentsRows += tr("最近回款时间", fmtDateTime(b.paymentsAgg.latestPaidAt()));

    return template
        .replace("{{CASE_ID}}", esc(b.caseId.toString()))
        .replace("{{CASE_CODE}}", esc(b.caseCode()))
        .replace("{{CASE_INFO_ROWS}}", infoRows)
        .replace("{{MEMBERS_ROWS}}", membersRows)
        .replace("{{TASKS_ROWS}}", tasksRows)
        .replace("{{INSTRUCTION_OVERDUE_TOTAL}}", String.valueOf(overdueTotal))
        .replace("{{INSTRUCTIONS_ROWS}}", insRows)
        .replace("{{PAYMENTS_ROWS}}", paymentsRows);
  }

  private byte[] htmlToPdfBytes(String html) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      PdfRendererBuilder builder = new PdfRendererBuilder();
      builder.withHtmlContent(html, null);
      builder.toStream(out);
      builder.run();
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("PDF_RENDER_FAILED");
    }
  }

  private static String readClasspathText(String path) {
    try {
      ClassPathResource r = new ClassPathResource(path);
      byte[] bytes = r.getInputStream().readAllBytes();
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("TEMPLATE_NOT_FOUND");
    }
  }

  private void writeAudit(HttpServletRequest httpReq,
                          UUID actorUserId,
                          UUID groupId,
                          String action,
                          String targetType,
                          UUID targetId,
                          String detailJson) {
    String rid = (String) httpReq.getAttribute(RequestIdFilter.REQ_ID_ATTR);
    jdbc.update(
        """
        insert into audit_log(group_id, actor_user_id, action, object_type, object_id, request_id, ip, user_agent, summary)
        values (?,?,?,?,?,?,?,?, ?::jsonb)
        """,
        groupId,
        actorUserId,
        action,
        targetType,
        targetId,
        rid,
        httpReq.getRemoteAddr(),
        httpReq.getHeader("User-Agent"),
        detailJson
    );
  }

  private static String esc(String s) {
    if (s == null) return "";
    return s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static String tr(String k, String v) {
    return "<tr><th class=\"k\">" + esc(k) + "</th><td>" + esc(v) + "</td></tr>";
  }

  private static String tr4(String c1, String c2, String c3, String c4) {
    return "<tr><td>" + esc(c1) + "</td><td>" + esc(c2) + "</td><td>" + esc(c3) + "</td><td>" + esc(c4) + "</td></tr>";
  }

  private static String tr6(String c1, String c2, String c3, String c4, String c5, String c6) {
    return "<tr><td>" + esc(c1) + "</td><td>" + esc(c2) + "</td><td>" + esc(c3) + "</td><td>" + esc(c4) + "</td><td>" + esc(c5) + "</td><td>" + esc(c6) + "</td></tr>";
  }

  private static String tr9(String c1, String c2, String c3, String c4, String c5, String c6, String c7, String c8, String c9) {
    return "<tr><td>" + esc(c1) + "</td><td>" + esc(c2) + "</td><td>" + esc(c3) + "</td><td>" + esc(c4) + "</td><td>" + esc(c5) + "</td><td>" + esc(c6) + "</td><td>" + esc(c7) + "</td><td>" + esc(c8) + "</td><td>" + esc(c9) + "</td></tr>";
  }

  private static String fmtMoney(BigDecimal v) {
    if (v == null) return "0";
    return v.stripTrailingZeros().toPlainString();
  }

  private static OffsetDateTime toOffsetDateTime(Object v) {
    if (v == null) return null;
    if (v instanceof OffsetDateTime odt) return odt;
    if (v instanceof Timestamp ts) return ts.toInstant().atOffset(ZoneOffset.UTC);
    if (v instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC);
    return null;
  }

  private static LocalDate toLocalDate(Object v) {
    if (v == null) return null;
    if (v instanceof LocalDate ld) return ld;
    if (v instanceof java.sql.Date d) return d.toLocalDate();
    if (v instanceof Timestamp ts) return ts.toLocalDateTime().toLocalDate();
    if (v instanceof LocalDateTime ldt) return ldt.toLocalDate();
    return LocalDate.parse(String.valueOf(v));
  }

  private static String fmtDateTime(Object v) {
    OffsetDateTime odt = toOffsetDateTime(v);
    if (odt == null) return "";
    return odt.toString();
  }

  private static String maskPhone(String phone) {
    String p = phone == null ? "" : phone;
    if (p.length() < 7) return p;
    return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
  }
}
