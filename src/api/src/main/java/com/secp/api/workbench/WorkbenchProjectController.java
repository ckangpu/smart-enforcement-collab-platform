package com.secp.api.workbench;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.workbench.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/workbench")
public class WorkbenchProjectController {

  private final WorkbenchProjectService service;

  @GetMapping("/projects/meta")
  public WorkbenchMetaResponse meta() {
    AuthPrincipal principal = AuthContext.getRequired();
    return service.meta(principal);
  }

  @GetMapping("/groups/{groupId}/members")
  public ResponseEntity<List<GroupMemberDto>> groupMembers(@PathVariable UUID groupId) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.groupMembers(principal, groupId));
  }

  @PostMapping("/projects")
  public ResponseEntity<CreateWorkbenchProjectResponse> createProject(@Valid @RequestBody CreateWorkbenchProjectRequest req,
                                                                      HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.createProject(principal, req, httpReq));
  }

  @PatchMapping("/projects/{projectId}")
  public ResponseEntity<Void> updateProject(@PathVariable UUID projectId,
                                            @Valid @RequestBody UpdateWorkbenchProjectRequest req,
                                            HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.updateProject(principal, projectId, req, httpReq);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/projects/{projectId}/detail")
  public ResponseEntity<WorkbenchProjectDetailDto> projectDetail(@PathVariable UUID projectId) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.projectDetail(principal, projectId));
  }

  // -------- 本项目案件（A + 执行依据） --------
  @PostMapping("/projects/{projectId}/cases")
  public ResponseEntity<IdResponse> createCase(@PathVariable UUID projectId,
                                               @Valid @RequestBody CreateWorkbenchCaseRequest req,
                                               HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.createCase(principal, projectId, req, httpReq));
  }

  // -------- 申请执行人（SR） --------
  @PostMapping("/projects/{projectId}/creditors")
  public ResponseEntity<IdResponse> createCreditor(@PathVariable UUID projectId,
                                                   @Valid @RequestBody CreateCreditorRequest req,
                                                   HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.createCreditor(principal, projectId, req, httpReq));
  }

  @PutMapping("/creditors/{creditorId}")
  public ResponseEntity<Void> updateCreditor(@PathVariable UUID creditorId,
                                             @Valid @RequestBody UpdateCreditorRequest req,
                                             HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.updateCreditor(principal, creditorId, req, httpReq);
    return ResponseEntity.ok().build();
  }

  @DeleteMapping("/creditors/{creditorId}")
  public ResponseEntity<Void> deleteCreditor(@PathVariable UUID creditorId, HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.deleteCreditor(principal, creditorId, httpReq);
    return ResponseEntity.ok().build();
  }

  // -------- 被执行人（BR） --------
  @PostMapping("/projects/{projectId}/debtors")
  public ResponseEntity<IdResponse> createDebtor(@PathVariable UUID projectId,
                                                 @Valid @RequestBody CreateDebtorRequest req,
                                                 HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.createDebtor(principal, projectId, req, httpReq));
  }

  @PutMapping("/debtors/{debtorId}")
  public ResponseEntity<Void> updateDebtor(@PathVariable UUID debtorId,
                                           @Valid @RequestBody UpdateDebtorRequest req,
                                           HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.updateDebtor(principal, debtorId, req, httpReq);
    return ResponseEntity.ok().build();
  }

  @DeleteMapping("/debtors/{debtorId}")
  public ResponseEntity<Void> deleteDebtor(@PathVariable UUID debtorId, HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.deleteDebtor(principal, debtorId, httpReq);
    return ResponseEntity.ok().build();
  }

  // -------- 被执行人线索（XS） --------
  @GetMapping("/debtors/{debtorId}/clues")
  public ResponseEntity<List<DebtorClueDto>> listClues(@PathVariable UUID debtorId) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.listClues(principal, debtorId));
  }

  @PostMapping("/debtors/{debtorId}/clues")
  public ResponseEntity<IdResponse> createClue(@PathVariable UUID debtorId,
                                               @Valid @RequestBody CreateClueRequest req,
                                               HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.createClue(principal, debtorId, req, httpReq));
  }

  @PutMapping("/clues/{clueId}")
  public ResponseEntity<Void> updateClue(@PathVariable UUID clueId,
                                         @Valid @RequestBody UpdateClueRequest req,
                                         HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.updateClue(principal, clueId, req, httpReq);
    return ResponseEntity.ok().build();
  }

  @DeleteMapping("/clues/{clueId}")
  public ResponseEntity<Void> deleteClue(@PathVariable UUID clueId, HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.deleteClue(principal, clueId, httpReq);
    return ResponseEntity.ok().build();
  }

  // -------- 案件基础信息（绑定 SR/BR 与 执行依据等） --------
  @PatchMapping("/cases/{caseId}")
  public ResponseEntity<Void> updateCase(@PathVariable UUID caseId,
                                         @Valid @RequestBody UpdateWorkbenchCaseRequest req,
                                         HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.updateCase(principal, caseId, req, httpReq);
    return ResponseEntity.ok().build();
  }

  // -------- 程序（case_procedure） --------
  @GetMapping("/cases/{caseId}/procedures")
  public ResponseEntity<List<CaseProcedureDto>> listProcedures(@PathVariable UUID caseId) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.listProcedures(principal, caseId));
  }

  @PostMapping("/cases/{caseId}/procedures")
  public ResponseEntity<IdResponse> createProcedure(@PathVariable UUID caseId,
                                                    @Valid @RequestBody CreateCaseProcedureRequest req,
                                                    HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.createProcedure(principal, caseId, req, httpReq));
  }

  @PutMapping("/procedures/{procedureId}")
  public ResponseEntity<Void> updateProcedure(@PathVariable UUID procedureId,
                                              @Valid @RequestBody UpdateCaseProcedureRequest req,
                                              HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.updateProcedure(principal, procedureId, req, httpReq);
    return ResponseEntity.ok().build();
  }

  @DeleteMapping("/procedures/{procedureId}")
  public ResponseEntity<Void> deleteProcedure(@PathVariable UUID procedureId, HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.deleteProcedure(principal, procedureId, httpReq);
    return ResponseEntity.ok().build();
  }

  // -------- 控制措施（case_measure_control） --------
  @GetMapping("/cases/{caseId}/measures/control")
  public ResponseEntity<List<CaseMeasureDto>> listControlMeasures(@PathVariable UUID caseId) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.listControlMeasures(principal, caseId));
  }

  @PostMapping("/cases/{caseId}/measures/control")
  public ResponseEntity<IdResponse> createControlMeasure(@PathVariable UUID caseId,
                                                         @Valid @RequestBody CreateCaseMeasureRequest req,
                                                         HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.createControlMeasure(principal, caseId, req, httpReq));
  }

  @PutMapping("/measures/control/{measureId}")
  public ResponseEntity<Void> updateControlMeasure(@PathVariable UUID measureId,
                                                   @Valid @RequestBody UpdateCaseMeasureRequest req,
                                                   HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.updateControlMeasure(principal, measureId, req, httpReq);
    return ResponseEntity.ok().build();
  }

  @DeleteMapping("/measures/control/{measureId}")
  public ResponseEntity<Void> deleteControlMeasure(@PathVariable UUID measureId, HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.deleteControlMeasure(principal, measureId, httpReq);
    return ResponseEntity.ok().build();
  }

  // -------- 惩戒措施（case_measure_sanction） --------
  @GetMapping("/cases/{caseId}/measures/sanction")
  public ResponseEntity<List<CaseMeasureDto>> listSanctionMeasures(@PathVariable UUID caseId) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.listSanctionMeasures(principal, caseId));
  }

  @PostMapping("/cases/{caseId}/measures/sanction")
  public ResponseEntity<IdResponse> createSanctionMeasure(@PathVariable UUID caseId,
                                                          @Valid @RequestBody CreateCaseMeasureRequest req,
                                                          HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.createSanctionMeasure(principal, caseId, req, httpReq));
  }

  @PutMapping("/measures/sanction/{measureId}")
  public ResponseEntity<Void> updateSanctionMeasure(@PathVariable UUID measureId,
                                                    @Valid @RequestBody UpdateCaseMeasureRequest req,
                                                    HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.updateSanctionMeasure(principal, measureId, req, httpReq);
    return ResponseEntity.ok().build();
  }

  @DeleteMapping("/measures/sanction/{measureId}")
  public ResponseEntity<Void> deleteSanctionMeasure(@PathVariable UUID measureId, HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.deleteSanctionMeasure(principal, measureId, httpReq);
    return ResponseEntity.ok().build();
  }

  // -------- 费用（case_cost） --------
  @GetMapping("/cases/{caseId}/costs")
  public ResponseEntity<List<CaseCostDto>> listCosts(@PathVariable UUID caseId) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.listCosts(principal, caseId));
  }

  @PostMapping("/cases/{caseId}/costs")
  public ResponseEntity<IdResponse> createCost(@PathVariable UUID caseId,
                                               @Valid @RequestBody CreateCaseCostRequest req,
                                               HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.createCost(principal, caseId, req, httpReq));
  }

  @PutMapping("/costs/{costId}")
  public ResponseEntity<Void> updateCost(@PathVariable UUID costId,
                                         @Valid @RequestBody UpdateCaseCostRequest req,
                                         HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.updateCost(principal, costId, req, httpReq);
    return ResponseEntity.ok().build();
  }

  @DeleteMapping("/costs/{costId}")
  public ResponseEntity<Void> deleteCost(@PathVariable UUID costId, HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.deleteCost(principal, costId, httpReq);
    return ResponseEntity.ok().build();
  }

  // -------- 证明材料（attachment_link） --------
  @GetMapping("/attachments")
  public ResponseEntity<List<AttachmentLinkDto>> listAttachments(@RequestParam String objectType,
                                                                 @RequestParam UUID objectId) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.listAttachments(principal, objectType, objectId));
  }

  @PostMapping("/attachments")
  public ResponseEntity<IdResponse> createAttachment(@Valid @RequestBody CreateAttachmentLinkRequest req,
                                                     HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(service.createAttachment(principal, req, httpReq));
  }

  @DeleteMapping("/attachments/{attachmentId}")
  public ResponseEntity<Void> deleteAttachment(@PathVariable UUID attachmentId, HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    service.deleteAttachment(principal, attachmentId, httpReq);
    return ResponseEntity.ok().build();
  }
}
