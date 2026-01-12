package com.secp.api.workbench;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.idempotency.ResponseJson;
import com.secp.api.infra.BizCodeService;
import com.secp.api.infra.RequestIdFilter;
import com.secp.api.infra.tx.TransactionalExecutor;
import com.secp.api.project.ProjectNotFoundException;
import com.secp.api.workbench.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkbenchProjectService {

  private final TransactionalExecutor tx;
  private final JdbcTemplate jdbc;
  private final WorkbenchProjectRepository repo;
  private final BizCodeService bizCodeService;
  private final ResponseJson responseJson;

  @Value("${secp.workbench.case-dedup.strategy:PARTIES_CAUSE_BASIS}")
  private String caseDedupStrategy;

  public WorkbenchMetaResponse meta(AuthPrincipal principal) {
    return tx.execute(principal, () -> {
      List<GroupOptionDto> groups = repo.listMyGroups();
      return new WorkbenchMetaResponse(groups);
    });
  }

  public List<GroupMemberDto> groupMembers(AuthPrincipal principal, UUID groupId) {
    return tx.execute(principal, () -> {
      if (!Boolean.TRUE.equals(jdbc.queryForObject("select app_is_admin() or ? = any(app_group_ids())", Boolean.class, groupId))) {
        throw new ProjectNotFoundException();
      }
      return repo.listGroupMembers(groupId);
    });
  }

  public CreateWorkbenchProjectResponse createProject(AuthPrincipal principal,
                                                     CreateWorkbenchProjectRequest req,
                                                     HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      Boolean allowed = jdbc.queryForObject("select app_can_write_group(?)", Boolean.class, req.groupId());
      if (!Boolean.TRUE.equals(allowed)) {
        throw new ProjectNotFoundException();
      }

      String code = resolveBizCode("X", req.acceptedAt(), req.codeSource(), req.manualCode());

      UUID projectId = UUID.randomUUID();
      try {
        repo.insertProject(projectId, req, code, principal.userId());
      } catch (DuplicateKeyException e) {
        throw new WorkbenchValidationException("项目编号已存在，请更换编号后重试。", 409);
      }

      writeAudit(httpReq, principal.userId(), req.groupId(),
          "Workbench.ProjectCreated",
          "project",
          projectId,
          responseJson.toJson(Map.of(
              "projectId", projectId,
              "groupId", req.groupId(),
              "code", code
          ))
      );
      writeOutbox(req.groupId(), projectId, null, principal.userId(),
          "Project.Created",
          "Project.Created:project:" + projectId + ":v1",
          responseJson.toJson(Map.of(
              "projectId", projectId,
              "groupId", req.groupId(),
              "code", code
          ))
      );

      return new CreateWorkbenchProjectResponse(projectId, req.groupId(), code);
    });
  }

  public void updateProject(AuthPrincipal principal, UUID projectId, UpdateWorkbenchProjectRequest req, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var project = repo.findProjectBase(projectId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) project.get("group_id");

      Boolean allowed = jdbc.queryForObject("select app_can_write_group(?)", Boolean.class, groupId);
      if (!Boolean.TRUE.equals(allowed)) {
        throw new ProjectNotFoundException();
      }

      repo.updateProject(projectId, req);

      writeAudit(httpReq, principal.userId(), groupId,
          "Workbench.ProjectUpdated",
          "project",
          projectId,
          responseJson.toJson(Map.of(
              "projectId", projectId,
              "fields", req.changedFields()
          ))
      );
      writeOutbox(groupId, projectId, null, principal.userId(),
          "Project.Updated",
          "Project.Updated:project:" + projectId + ":v1",
          responseJson.toJson(Map.of(
              "projectId", projectId
          ))
      );
    });
  }

  public WorkbenchProjectDetailDto projectDetail(AuthPrincipal principal, UUID projectId) {
    return tx.execute(principal, () -> {
      var p = repo.findProjectDetail(projectId).orElseThrow(ProjectNotFoundException::new);
      List<WorkbenchCreditorDto> creditors = repo.listCreditors(projectId);
      List<WorkbenchDebtorDto> debtors = repo.listDebtors(projectId);
      List<WorkbenchCaseDto> cases = repo.listCases(projectId);
      return new WorkbenchProjectDetailDto(p, creditors, debtors, cases);
    });
  }

  public IdResponse createCreditor(AuthPrincipal principal, UUID projectId, CreateCreditorRequest req, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      var project = repo.findProjectDetailRow(projectId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) project.get("group_id");
      LocalDate acceptedAt = project.get("accepted_at") == null ? null : ((java.sql.Date) project.get("accepted_at")).toLocalDate();

      assertCanWriteGroup(groupId);

      // 重复主体识别：同项目内若主体已存在则复用编号。
      var existing = repo.findExistingCreditorForProject(projectId, req.name(), req.idNo(), req.unifiedCode());
      if (existing.isPresent()) {
        UUID creditorId = (UUID) existing.get().get("id");
        String srCode = String.valueOf(existing.get().get("sr_code"));
        writeAudit(httpReq, principal.userId(), groupId, "Workbench.CreditorDedup", "project_creditor", creditorId,
            responseJson.toJson(Map.of("creditorId", creditorId, "projectId", projectId, "srCode", srCode)));
        return new IdResponse(creditorId);
      }

      String srCode = resolveBizCode("SR", acceptedAt, req.codeSource(), req.manualSrCode());

      UUID creditorId = UUID.randomUUID();
      try {
        repo.insertCreditor(creditorId, groupId, projectId, srCode, req, principal.userId());
      } catch (DuplicateKeyException e) {
        throw new WorkbenchValidationException("申请执行人编号已存在，请更换编号后重试。", 409);
      }

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.CreditorCreated", "project_creditor", creditorId,
          responseJson.toJson(Map.of("creditorId", creditorId, "projectId", projectId, "srCode", srCode)));
      writeOutbox(groupId, projectId, null, principal.userId(), "Creditor.Created",
          "Creditor.Created:project_creditor:" + creditorId + ":v1",
          responseJson.toJson(Map.of("creditorId", creditorId, "projectId", projectId, "srCode", srCode)));

      return new IdResponse(creditorId);
    });
  }

  public void updateCreditor(AuthPrincipal principal, UUID creditorId, UpdateCreditorRequest req, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findCreditorBase(creditorId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      assertCanWriteGroup(groupId);

      repo.updateCreditor(creditorId, req);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.CreditorUpdated", "project_creditor", creditorId,
          responseJson.toJson(Map.of("creditorId", creditorId, "projectId", projectId)));
      writeOutbox(groupId, projectId, null, principal.userId(), "Creditor.Updated",
          "Creditor.Updated:project_creditor:" + creditorId + ":v1",
          responseJson.toJson(Map.of("creditorId", creditorId, "projectId", projectId)));
    });
  }

  public void deleteCreditor(AuthPrincipal principal, UUID creditorId, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findCreditorBase(creditorId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      assertCanWriteGroup(groupId);

      repo.deleteCreditor(creditorId);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.CreditorDeleted", "project_creditor", creditorId,
          responseJson.toJson(Map.of("creditorId", creditorId, "projectId", projectId)));
      writeOutbox(groupId, projectId, null, principal.userId(), "Creditor.Deleted",
          "Creditor.Deleted:project_creditor:" + creditorId + ":v1",
          responseJson.toJson(Map.of("creditorId", creditorId, "projectId", projectId)));
    });
  }

  public IdResponse createDebtor(AuthPrincipal principal, UUID projectId, CreateDebtorRequest req, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      var project = repo.findProjectDetailRow(projectId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) project.get("group_id");
      LocalDate acceptedAt = project.get("accepted_at") == null ? null : ((java.sql.Date) project.get("accepted_at")).toLocalDate();
      assertCanWriteGroup(groupId);

      // 重复主体识别：同项目内若主体已存在则复用编号。
      var existing = repo.findExistingDebtorForProject(projectId, req.name(), req.idNo(), req.unifiedCode());
      if (existing.isPresent()) {
        UUID debtorId = (UUID) existing.get().get("id");
        String brCode = String.valueOf(existing.get().get("br_code"));
        writeAudit(httpReq, principal.userId(), groupId, "Workbench.DebtorDedup", "project_debtor", debtorId,
            responseJson.toJson(Map.of("debtorId", debtorId, "projectId", projectId, "brCode", brCode)));
        return new IdResponse(debtorId);
      }

      String brCode = resolveBizCode("BR", acceptedAt, req.codeSource(), req.manualBrCode());
      UUID debtorId = UUID.randomUUID();
      try {
        repo.insertDebtor(debtorId, groupId, projectId, brCode, req, principal.userId());
      } catch (DuplicateKeyException e) {
        throw new WorkbenchValidationException("被执行人编号已存在，请更换编号后重试。", 409);
      }

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.DebtorCreated", "project_debtor", debtorId,
          responseJson.toJson(Map.of("debtorId", debtorId, "projectId", projectId, "brCode", brCode)));
      writeOutbox(groupId, projectId, null, principal.userId(), "Debtor.Created",
          "Debtor.Created:project_debtor:" + debtorId + ":v1",
          responseJson.toJson(Map.of("debtorId", debtorId, "projectId", projectId, "brCode", brCode)));

      return new IdResponse(debtorId);
    });
  }

  public void updateDebtor(AuthPrincipal principal, UUID debtorId, UpdateDebtorRequest req, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findDebtorBase(debtorId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      assertCanWriteGroup(groupId);

      repo.updateDebtor(debtorId, req);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.DebtorUpdated", "project_debtor", debtorId,
          responseJson.toJson(Map.of("debtorId", debtorId, "projectId", projectId)));
      writeOutbox(groupId, projectId, null, principal.userId(), "Debtor.Updated",
          "Debtor.Updated:project_debtor:" + debtorId + ":v1",
          responseJson.toJson(Map.of("debtorId", debtorId, "projectId", projectId)));
    });
  }

  public void deleteDebtor(AuthPrincipal principal, UUID debtorId, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findDebtorBase(debtorId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      assertCanWriteGroup(groupId);

      repo.deleteDebtor(debtorId);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.DebtorDeleted", "project_debtor", debtorId,
          responseJson.toJson(Map.of("debtorId", debtorId, "projectId", projectId)));
      writeOutbox(groupId, projectId, null, principal.userId(), "Debtor.Deleted",
          "Debtor.Deleted:project_debtor:" + debtorId + ":v1",
          responseJson.toJson(Map.of("debtorId", debtorId, "projectId", projectId)));
    });
  }

  public List<DebtorClueDto> listClues(AuthPrincipal principal, UUID debtorId) {
    return tx.execute(principal, () -> repo.listClues(debtorId));
  }

  public IdResponse createClue(AuthPrincipal principal, UUID debtorId, CreateClueRequest req, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      var row = repo.findDebtorBase(debtorId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      var project = repo.findProjectDetailRow(projectId).orElseThrow(ProjectNotFoundException::new);
      LocalDate acceptedAt = project.get("accepted_at") == null ? null : ((java.sql.Date) project.get("accepted_at")).toLocalDate();

      assertCanWriteGroup(groupId);

      String xsCode = resolveBizCode("XS", acceptedAt, req.codeSource(), req.manualXsCode());
      UUID clueId = UUID.randomUUID();
      try {
        repo.insertClue(clueId, groupId, debtorId, xsCode, req, principal.userId());
      } catch (DuplicateKeyException e) {
        throw new WorkbenchValidationException("线索编号已存在，请更换编号后重试。", 409);
      }

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.ClueCreated", "debtor_clue", clueId,
          responseJson.toJson(Map.of("clueId", clueId, "projectId", projectId, "debtorId", debtorId, "xsCode", xsCode)));
      writeOutbox(groupId, projectId, null, principal.userId(), "Clue.Created",
          "Clue.Created:debtor_clue:" + clueId + ":v1",
          responseJson.toJson(Map.of("clueId", clueId, "projectId", projectId, "debtorId", debtorId, "xsCode", xsCode)));

      return new IdResponse(clueId);
    });
  }

  public void updateClue(AuthPrincipal principal, UUID clueId, UpdateClueRequest req, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findClueBase(clueId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID debtorId = (UUID) row.get("debtor_id");
      UUID projectId = (UUID) row.get("project_id");
      assertCanWriteGroup(groupId);
      repo.updateClue(clueId, req);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.ClueUpdated", "debtor_clue", clueId,
          responseJson.toJson(Map.of("clueId", clueId, "projectId", projectId, "debtorId", debtorId)));
      writeOutbox(groupId, projectId, null, principal.userId(), "Clue.Updated",
          "Clue.Updated:debtor_clue:" + clueId + ":v1",
          responseJson.toJson(Map.of("clueId", clueId, "projectId", projectId, "debtorId", debtorId)));
    });
  }

  public void deleteClue(AuthPrincipal principal, UUID clueId, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findClueBase(clueId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      assertCanWriteGroup(groupId);

      repo.deleteClue(clueId);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.ClueDeleted", "debtor_clue", clueId,
          responseJson.toJson(Map.of("clueId", clueId, "projectId", projectId)));
      writeOutbox(groupId, projectId, null, principal.userId(), "Clue.Deleted",
          "Clue.Deleted:debtor_clue:" + clueId + ":v1",
          responseJson.toJson(Map.of("clueId", clueId, "projectId", projectId)));
    });
  }

  public void updateCase(AuthPrincipal principal, UUID caseId, UpdateWorkbenchCaseRequest req, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findCaseBase(caseId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      assertCanWriteGroup(groupId);

      if (req.creditorId() != null && !repo.creditorBelongsToProject(req.creditorId(), projectId)) {
        throw new WorkbenchValidationException("申请执行人不属于当前项目。", 422);
      }
      if (req.debtorId() != null && !repo.debtorBelongsToProject(req.debtorId(), projectId)) {
        throw new WorkbenchValidationException("被执行人不属于当前项目。", 422);
      }

      repo.updateCase(caseId, req);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.CaseUpdated", "case", caseId,
          responseJson.toJson(Map.of("caseId", caseId, "projectId", projectId)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "Case.Updated",
          "Case.Updated:case:" + caseId + ":v1",
          responseJson.toJson(Map.of("caseId", caseId, "projectId", projectId)));
    });
  }

  public IdResponse createCase(AuthPrincipal principal, UUID projectId, CreateWorkbenchCaseRequest req, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      var project = repo.findProjectDetailRow(projectId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) project.get("group_id");
      LocalDate acceptedAt = project.get("accepted_at") == null ? null : ((java.sql.Date) project.get("accepted_at")).toLocalDate();
      assertCanWriteGroup(groupId);

      if (!repo.creditorBelongsToProject(req.creditorId(), projectId)) {
        throw new WorkbenchValidationException("申请执行人不属于当前项目。", 422);
      }
      if (!repo.debtorBelongsToProject(req.debtorId(), projectId)) {
        throw new WorkbenchValidationException("被执行人不属于当前项目。", 422);
      }

      // 可配置去重：仅 AUTO 编号时生效，避免手动编号/明确意图时被复用。
      if (!isManualCode(req.codeSource(), req.manualCaseCode())) {
        var existing = findExistingCaseForDedupe(projectId, req);
        if (existing.isPresent()) {
          UUID existingCaseId = (UUID) existing.get().get("id");
          String existingCode = String.valueOf(existing.get().get("code"));
          writeAudit(httpReq, principal.userId(), groupId, "Workbench.CaseDedup", "case", existingCaseId,
              responseJson.toJson(Map.of(
                  "caseId", existingCaseId,
                  "caseCode", existingCode,
                  "projectId", projectId,
                  "creditorId", req.creditorId(),
                  "debtorId", req.debtorId(),
                  "cause", req.cause(),
                  "strategy", caseDedupStrategy
              )));
          return new IdResponse(existingCaseId);
        }
      }

      String code = resolveBizCode("A", acceptedAt, req.codeSource(), req.manualCaseCode());

      String creditorName = "";
      for (var c : repo.listCreditors(projectId)) {
        if (req.creditorId().equals(c.creditorId())) {
          creditorName = c.name();
          break;
        }
      }
      String debtorName = "";
      for (var d : repo.listDebtors(projectId)) {
        if (req.debtorId().equals(d.debtorId())) {
          debtorName = d.name();
          break;
        }
      }
      String title = buildCaseTitle(req.cause(), creditorName, debtorName);

      UUID caseId = UUID.randomUUID();
      try {
        repo.insertWorkbenchCase(
            caseId,
            groupId,
            projectId,
            code,
            acceptedAt,
            title,
            req.creditorId(),
            req.debtorId(),
            req.cause(),
            req.basisDocType(),
            req.basisDocNo(),
            req.basisOrg(),
            req.basisDecidedAt(),
            req.basisMainText(),
            principal.userId()
        );
      } catch (DuplicateKeyException e) {
        throw new WorkbenchValidationException("案件编号已存在，请更换编号后重试。", 409);
      }

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.CaseCreated", "case", caseId,
          responseJson.toJson(Map.of("caseId", caseId, "projectId", projectId, "code", code)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "Case.Created",
          "Case.Created:case:" + caseId + ":v1",
          responseJson.toJson(Map.of("caseId", caseId, "projectId", projectId, "groupId", groupId, "code", code)));

      return new IdResponse(caseId);
    });
  }

  private enum CaseDedupStrategy {
    NONE,
    PARTIES_CAUSE,
    PARTIES_CAUSE_BASIS
  }

  private Optional<Map<String, Object>> findExistingCaseForDedupe(UUID projectId, CreateWorkbenchCaseRequest req) {
    CaseDedupStrategy s = parseCaseDedupStrategy(caseDedupStrategy);
    if (s == CaseDedupStrategy.NONE) return Optional.empty();

    return switch (s) {
      case PARTIES_CAUSE -> repo.findExistingCaseForProjectPartiesCause(projectId, req.creditorId(), req.debtorId(), req.cause());
      case PARTIES_CAUSE_BASIS -> repo.findExistingCaseForProjectPartiesCauseBasis(
          projectId,
          req.creditorId(),
          req.debtorId(),
          req.cause(),
          req.basisDocType(),
          req.basisDocNo(),
          req.basisOrg(),
          req.basisDecidedAt()
      );
      case NONE -> Optional.empty();
    };
  }

  private static CaseDedupStrategy parseCaseDedupStrategy(String raw) {
    String v = raw == null ? "" : raw.trim().toUpperCase();
    if (v.isBlank()) return CaseDedupStrategy.PARTIES_CAUSE_BASIS;
    try {
      return CaseDedupStrategy.valueOf(v);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("secp.workbench.case-dedup.strategy 配置不合法：" + raw + "（允许：NONE / PARTIES_CAUSE / PARTIES_CAUSE_BASIS）");
    }
  }

  private static boolean isManualCode(String codeSource, String manualCode) {
    String src = codeSource == null ? "AUTO" : codeSource.trim().toUpperCase();
    return "MANUAL".equals(src) || (manualCode != null && !manualCode.isBlank());
  }

  public List<AttachmentLinkDto> listAttachments(AuthPrincipal principal, String objectType, UUID objectId) {
    return tx.execute(principal, () -> repo.listAttachments(objectType, objectId));
  }

  public IdResponse createAttachment(AuthPrincipal principal, CreateAttachmentLinkRequest req, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      UUID groupId = repo.resolveGroupIdForObject(req.objectType(), req.objectId()).orElseThrow(ProjectNotFoundException::new);
      assertCanWriteGroup(groupId);

      UUID projectId = repo.resolveProjectIdForObject(req.objectType(), req.objectId()).orElseThrow(ProjectNotFoundException::new);

      var file = repo.findFile(req.fileId()).orElseThrow(() -> new WorkbenchValidationException("文件不存在或不可访问。", 422));
      if (file.projectId() == null || !file.projectId().equals(projectId)) {
        throw new WorkbenchValidationException("文件不属于当前项目。", 422);
      }
      if (!"READY".equalsIgnoreCase(file.status())) {
        throw new WorkbenchValidationException("文件未就绪，请稍后重试。", 422);
      }

      UUID attachmentId = UUID.randomUUID();
      repo.insertAttachment(attachmentId, groupId, req, principal.userId());

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.AttachmentLinked", "attachment_link", attachmentId,
          responseJson.toJson(Map.of("attachmentId", attachmentId, "objectType", req.objectType(), "objectId", req.objectId(), "fileId", req.fileId())));
      writeOutbox(groupId, projectId, null, principal.userId(), "Attachment.Linked",
          "Attachment.Linked:attachment_link:" + attachmentId + ":v1",
          responseJson.toJson(Map.of("attachmentId", attachmentId, "objectType", req.objectType(), "objectId", req.objectId(), "fileId", req.fileId())));

      return new IdResponse(attachmentId);
    });
  }

  public void deleteAttachment(AuthPrincipal principal, UUID attachmentId, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findAttachmentBase(attachmentId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      assertCanWriteGroup(groupId);

      repo.deleteAttachment(attachmentId);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.AttachmentUnlinked", "attachment_link", attachmentId,
          responseJson.toJson(Map.of("attachmentId", attachmentId)));
      writeOutbox(groupId, projectId, null, principal.userId(), "Attachment.Unlinked",
          "Attachment.Unlinked:attachment_link:" + attachmentId + ":v1",
          responseJson.toJson(Map.of("attachmentId", attachmentId)));
    });
  }

  public List<CaseProcedureDto> listProcedures(AuthPrincipal principal, UUID caseId) {
    return tx.execute(principal, () -> repo.listProcedures(caseId));
  }

  public IdResponse createProcedure(AuthPrincipal principal, UUID caseId, CreateCaseProcedureRequest req, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      var base = repo.findCaseBase(caseId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) base.get("group_id");
      UUID projectId = (UUID) base.get("project_id");
      assertCanWriteGroup(groupId);

      UUID procedureId = UUID.randomUUID();
      repo.insertProcedure(procedureId, groupId, caseId, req, principal.userId());

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.ProcedureCreated", "case_procedure", procedureId,
          responseJson.toJson(Map.of("procedureId", procedureId, "caseId", caseId, "projectId", projectId)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "Procedure.Created",
          "Procedure.Created:case_procedure:" + procedureId + ":v1",
          responseJson.toJson(Map.of("procedureId", procedureId, "caseId", caseId, "projectId", projectId)));

      return new IdResponse(procedureId);
    });
  }

  public void updateProcedure(AuthPrincipal principal, UUID procedureId, UpdateCaseProcedureRequest req, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findProcedureBase(procedureId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      UUID caseId = (UUID) row.get("case_id");
      assertCanWriteGroup(groupId);

      repo.updateProcedure(procedureId, req);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.ProcedureUpdated", "case_procedure", procedureId,
          responseJson.toJson(Map.of("procedureId", procedureId, "caseId", caseId, "projectId", projectId)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "Procedure.Updated",
          "Procedure.Updated:case_procedure:" + procedureId + ":v1",
          responseJson.toJson(Map.of("procedureId", procedureId, "caseId", caseId, "projectId", projectId)));
    });
  }

  public void deleteProcedure(AuthPrincipal principal, UUID procedureId, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findProcedureBase(procedureId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      UUID caseId = (UUID) row.get("case_id");
      assertCanWriteGroup(groupId);

      repo.deleteProcedure(procedureId);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.ProcedureDeleted", "case_procedure", procedureId,
          responseJson.toJson(Map.of("procedureId", procedureId, "caseId", caseId, "projectId", projectId)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "Procedure.Deleted",
          "Procedure.Deleted:case_procedure:" + procedureId + ":v1",
          responseJson.toJson(Map.of("procedureId", procedureId, "caseId", caseId, "projectId", projectId)));
    });
  }

  public List<CaseMeasureDto> listControlMeasures(AuthPrincipal principal, UUID caseId) {
    return tx.execute(principal, () -> repo.listControlMeasures(caseId));
  }

  public IdResponse createControlMeasure(AuthPrincipal principal, UUID caseId, CreateCaseMeasureRequest req, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      var base = repo.findCaseBase(caseId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) base.get("group_id");
      UUID projectId = (UUID) base.get("project_id");
      assertCanWriteGroup(groupId);

      UUID id = UUID.randomUUID();
      repo.insertControlMeasure(id, groupId, caseId, req, principal.userId());

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.ControlMeasureCreated", "case_measure_control", id,
          responseJson.toJson(Map.of("measureId", id, "caseId", caseId, "projectId", projectId)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "MeasureControl.Created",
          "MeasureControl.Created:case_measure_control:" + id + ":v1",
          responseJson.toJson(Map.of("measureId", id, "caseId", caseId, "projectId", projectId)));

      return new IdResponse(id);
    });
  }

  public void updateControlMeasure(AuthPrincipal principal, UUID measureId, UpdateCaseMeasureRequest req, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findControlMeasureBase(measureId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      UUID caseId = (UUID) row.get("case_id");
      assertCanWriteGroup(groupId);

      repo.updateControlMeasure(measureId, req);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.ControlMeasureUpdated", "case_measure_control", measureId,
          responseJson.toJson(Map.of("measureId", measureId, "caseId", caseId, "projectId", projectId)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "MeasureControl.Updated",
          "MeasureControl.Updated:case_measure_control:" + measureId + ":v1",
          responseJson.toJson(Map.of("measureId", measureId, "caseId", caseId, "projectId", projectId)));
    });
  }

  public void deleteControlMeasure(AuthPrincipal principal, UUID measureId, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findControlMeasureBase(measureId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      UUID caseId = (UUID) row.get("case_id");
      assertCanWriteGroup(groupId);

      repo.deleteControlMeasure(measureId);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.ControlMeasureDeleted", "case_measure_control", measureId,
          responseJson.toJson(Map.of("measureId", measureId, "caseId", caseId, "projectId", projectId)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "MeasureControl.Deleted",
          "MeasureControl.Deleted:case_measure_control:" + measureId + ":v1",
          responseJson.toJson(Map.of("measureId", measureId, "caseId", caseId, "projectId", projectId)));
    });
  }

  public List<CaseMeasureDto> listSanctionMeasures(AuthPrincipal principal, UUID caseId) {
    return tx.execute(principal, () -> repo.listSanctionMeasures(caseId));
  }

  public IdResponse createSanctionMeasure(AuthPrincipal principal, UUID caseId, CreateCaseMeasureRequest req, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      var base = repo.findCaseBase(caseId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) base.get("group_id");
      UUID projectId = (UUID) base.get("project_id");
      assertCanWriteGroup(groupId);

      UUID id = UUID.randomUUID();
      repo.insertSanctionMeasure(id, groupId, caseId, req, principal.userId());

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.SanctionMeasureCreated", "case_measure_sanction", id,
          responseJson.toJson(Map.of("measureId", id, "caseId", caseId, "projectId", projectId)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "MeasureSanction.Created",
          "MeasureSanction.Created:case_measure_sanction:" + id + ":v1",
          responseJson.toJson(Map.of("measureId", id, "caseId", caseId, "projectId", projectId)));

      return new IdResponse(id);
    });
  }

  public void updateSanctionMeasure(AuthPrincipal principal, UUID measureId, UpdateCaseMeasureRequest req, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findSanctionMeasureBase(measureId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      UUID caseId = (UUID) row.get("case_id");
      assertCanWriteGroup(groupId);

      repo.updateSanctionMeasure(measureId, req);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.SanctionMeasureUpdated", "case_measure_sanction", measureId,
          responseJson.toJson(Map.of("measureId", measureId, "caseId", caseId, "projectId", projectId)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "MeasureSanction.Updated",
          "MeasureSanction.Updated:case_measure_sanction:" + measureId + ":v1",
          responseJson.toJson(Map.of("measureId", measureId, "caseId", caseId, "projectId", projectId)));
    });
  }

  public void deleteSanctionMeasure(AuthPrincipal principal, UUID measureId, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findSanctionMeasureBase(measureId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      UUID caseId = (UUID) row.get("case_id");
      assertCanWriteGroup(groupId);

      repo.deleteSanctionMeasure(measureId);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.SanctionMeasureDeleted", "case_measure_sanction", measureId,
          responseJson.toJson(Map.of("measureId", measureId, "caseId", caseId, "projectId", projectId)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "MeasureSanction.Deleted",
          "MeasureSanction.Deleted:case_measure_sanction:" + measureId + ":v1",
          responseJson.toJson(Map.of("measureId", measureId, "caseId", caseId, "projectId", projectId)));
    });
  }

  public List<CaseCostDto> listCosts(AuthPrincipal principal, UUID caseId) {
    return tx.execute(principal, () -> repo.listCosts(caseId));
  }

  public IdResponse createCost(AuthPrincipal principal, UUID caseId, CreateCaseCostRequest req, HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      var base = repo.findCaseBase(caseId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) base.get("group_id");
      UUID projectId = (UUID) base.get("project_id");
      assertCanWriteGroup(groupId);

      UUID costId = UUID.randomUUID();
      repo.insertCost(costId, groupId, caseId, req, principal.userId());

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.CostCreated", "case_cost", costId,
          responseJson.toJson(Map.of("costId", costId, "caseId", caseId, "projectId", projectId)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "Cost.Created",
          "Cost.Created:case_cost:" + costId + ":v1",
          responseJson.toJson(Map.of("costId", costId, "caseId", caseId, "projectId", projectId)));

      return new IdResponse(costId);
    });
  }

  public void updateCost(AuthPrincipal principal, UUID costId, UpdateCaseCostRequest req, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findCostBase(costId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      UUID caseId = (UUID) row.get("case_id");
      assertCanWriteGroup(groupId);

      repo.updateCost(costId, req);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.CostUpdated", "case_cost", costId,
          responseJson.toJson(Map.of("costId", costId, "caseId", caseId, "projectId", projectId)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "Cost.Updated",
          "Cost.Updated:case_cost:" + costId + ":v1",
          responseJson.toJson(Map.of("costId", costId, "caseId", caseId, "projectId", projectId)));
    });
  }

  public void deleteCost(AuthPrincipal principal, UUID costId, HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var row = repo.findCostBase(costId).orElseThrow(ProjectNotFoundException::new);
      UUID groupId = (UUID) row.get("group_id");
      UUID projectId = (UUID) row.get("project_id");
      UUID caseId = (UUID) row.get("case_id");
      assertCanWriteGroup(groupId);

      repo.deleteCost(costId);

      writeAudit(httpReq, principal.userId(), groupId, "Workbench.CostDeleted", "case_cost", costId,
          responseJson.toJson(Map.of("costId", costId, "caseId", caseId, "projectId", projectId)));
      writeOutbox(groupId, projectId, caseId, principal.userId(), "Cost.Deleted",
          "Cost.Deleted:case_cost:" + costId + ":v1",
          responseJson.toJson(Map.of("costId", costId, "caseId", caseId, "projectId", projectId)));
    });
  }

  private void assertCanWriteGroup(UUID groupId) {
    Boolean allowed = jdbc.queryForObject("select app_can_write_group(?)", Boolean.class, groupId);
    if (!Boolean.TRUE.equals(allowed)) {
      throw new ProjectNotFoundException();
    }
  }

  private String resolveBizCode(String expectedPrefix,
                                LocalDate acceptedAt,
                                String codeSource,
                                String manualCode) {
    String src = codeSource == null ? "AUTO" : codeSource.trim().toUpperCase();
    if ("MANUAL".equals(src) || (manualCode != null && !manualCode.isBlank())) {
      String code = String.valueOf(manualCode == null ? "" : manualCode).trim();
      if (!isValidBizCode(code, expectedPrefix)) {
        throw new WorkbenchValidationException("项目编号格式不正确，应为“" + expectedPrefix + "YYYYMM0001”这类格式。", 422);
      }
      return code;
    }
    if (!"AUTO".equals(src)) {
      throw new WorkbenchValidationException("编号来源不合法，只允许 AUTO 或 MANUAL。", 422);
    }
    return bizCodeService.nextCode(expectedPrefix, acceptedAt);
  }

  private boolean isValidBizCode(String code, String expectedPrefix) {
    if (code == null) return false;
    String c = code.trim();
    if (!c.startsWith(expectedPrefix)) return false;
    if (!c.matches("^[A-Z]{1,8}\\d{6}\\d{4}$")) return false;
    String yyyymm = c.substring(expectedPrefix.length(), expectedPrefix.length() + 6);
    int mm = Integer.parseInt(yyyymm.substring(4, 6));
    return mm >= 1 && mm <= 12;
  }

  private static String buildCaseTitle(String cause, String creditorName, String debtorName) {
    String c = cause == null ? "" : cause.trim();
    String cr = creditorName == null ? "" : creditorName.trim();
    String db = debtorName == null ? "" : debtorName.trim();
    if (!c.isBlank() && !cr.isBlank() && !db.isBlank()) {
      return c + "（" + cr + " 对 " + db + "）";
    }
    if (!c.isBlank()) return c;
    if (!cr.isBlank() || !db.isBlank()) return (cr + " 对 " + db).trim();
    return "执行案件";
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

  private void writeOutbox(UUID groupId,
                           UUID projectId,
                           UUID caseId,
                           UUID actorUserId,
                           String eventType,
                           String dedupeKey,
                           String payloadJson) {
    jdbc.update(
        """
        insert into event_outbox(event_type, dedupe_key, group_id, project_id, case_id, actor_user_id, payload)
        values (?,?,?,?,?,?, ?::jsonb)
        """,
        eventType,
        dedupeKey,
        groupId,
        projectId,
        caseId,
        actorUserId,
        payloadJson
    );
  }
}
