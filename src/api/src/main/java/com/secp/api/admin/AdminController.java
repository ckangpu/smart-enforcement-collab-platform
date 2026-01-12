package com.secp.api.admin;

import com.secp.api.admin.dto.*;
import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminController {

  private final AdminService adminService;

  @PostMapping("/projects")
  public ResponseEntity<AdminCreateProjectResponse> createProject(@Valid @RequestBody AdminCreateProjectRequest req,
                                                                  HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    AdminCreateProjectResponse resp = adminService.createProject(principal, req, httpReq);
    return ResponseEntity.status(201).body(resp);
  }

  @PostMapping("/cases")
  public ResponseEntity<AdminCreateCaseResponse> createCase(@Valid @RequestBody AdminCreateCaseRequest req,
                                                            HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    AdminCreateCaseResponse resp = adminService.createCase(principal, req, httpReq);
    return ResponseEntity.status(201).body(resp);
  }

  @PostMapping("/projects/{projectId}/members")
  public ResponseEntity<AdminOkResponse> addProjectMember(@PathVariable UUID projectId,
                                                          @Valid @RequestBody AdminAddMemberRequest req,
                                                          HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    adminService.addProjectMember(principal, projectId, req, httpReq);
    return ResponseEntity.ok(new AdminOkResponse(true));
  }

  @PostMapping("/cases/{caseId}/members")
  public ResponseEntity<AdminOkResponse> addCaseMember(@PathVariable UUID caseId,
                                                       @Valid @RequestBody AdminAddMemberRequest req,
                                                       HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    adminService.addCaseMember(principal, caseId, req, httpReq);
    return ResponseEntity.ok(new AdminOkResponse(true));
  }

  // Optional read-only listing (RLS applies)
  @GetMapping("/projects")
  public ResponseEntity<List<AdminProjectListItem>> listProjects(@RequestParam UUID groupId) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(adminService.listProjects(principal, groupId));
  }

  @GetMapping("/cases")
  public ResponseEntity<List<AdminCaseListItem>> listCases(@RequestParam UUID projectId) {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(adminService.listCases(principal, projectId));
  }

  @PostMapping("/users/{userId}/password")
  public ResponseEntity<AdminOkResponse> setUserPassword(@PathVariable UUID userId,
                                                         @Valid @RequestBody AdminSetPasswordRequest req,
                                                         HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    adminService.setUserPassword(principal, userId, req, httpReq);
    return ResponseEntity.ok(new AdminOkResponse(true));
  }
}
