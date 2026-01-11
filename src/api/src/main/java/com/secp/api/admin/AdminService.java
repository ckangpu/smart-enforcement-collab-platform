package com.secp.api.admin;

import com.secp.api.admin.dto.*;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.infra.RequestIdFilter;
import com.secp.api.infra.tx.TransactionalExecutor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminService {

  private final TransactionalExecutor tx;
  private final JdbcTemplate jdbc;
  private final AdminRepository adminRepository;
  public AdminCreateProjectResponse createProject(AuthPrincipal principal,
                                                  AdminCreateProjectRequest req,
                                                  HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      Boolean allowed = jdbc.queryForObject("select app_can_write_group(?)", Boolean.class, req.groupId());
      if (!Boolean.TRUE.equals(allowed)) {
        // Do not leak group existence.
        throw new AdminNotFoundException();
      }

      UUID projectId = UUID.randomUUID();
      adminRepository.insertProject(
          projectId,
          req.groupId(),
          req.name(),
          principal.userId(),
          req.bizTags(),
          req.executionTargetAmount(),
          req.mandateAmount()
      );

      writeAudit(httpReq, principal.userId(), req.groupId(), "admin_project_create", "project", projectId,
          toJson(Map.of(
              "projectId", projectId,
              "groupId", req.groupId(),
              "name", req.name()
          ))
      );

      writeOutbox(req.groupId(), projectId, null, principal.userId(),
          "Project.Created",
          "Project.Created:project:" + projectId + ":v1",
          toJson(Map.of(
              "projectId", projectId,
              "groupId", req.groupId(),
              "name", req.name()
          ))
      );

      return new AdminCreateProjectResponse(projectId, req.groupId(), req.name());
    });
  }

  public AdminCreateCaseResponse createCase(AuthPrincipal principal,
                                            AdminCreateCaseRequest req,
                                            HttpServletRequest httpReq) {
    return tx.execute(principal, () -> {
      UUID groupId = adminRepository.findProjectGroupId(req.projectId())
          .orElseThrow(AdminNotFoundException::new);

      Boolean allowed = jdbc.queryForObject("select app_can_write_group(?)", Boolean.class, groupId);
      if (!Boolean.TRUE.equals(allowed)) {
        // Do not leak project existence.
        throw new AdminNotFoundException();
      }

      UUID caseId = UUID.randomUUID();
      adminRepository.insertCase(caseId, req.projectId(), groupId, req.title(), principal.userId());

      writeAudit(httpReq, principal.userId(), groupId, "admin_case_create", "case", caseId,
          toJson(Map.of(
              "caseId", caseId,
              "projectId", req.projectId(),
              "groupId", groupId
          ))
      );

      writeOutbox(groupId, req.projectId(), caseId, principal.userId(),
          "Case.Created",
          "Case.Created:case:" + caseId + ":v1",
          toJson(Map.of(
              "caseId", caseId,
              "projectId", req.projectId(),
              "groupId", groupId
          ))
      );

      return new AdminCreateCaseResponse(caseId, req.projectId(), groupId);
    });
  }

  public void addProjectMember(AuthPrincipal principal,
                               UUID projectId,
                               AdminAddMemberRequest req,
                               HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var project = adminRepository.findProject(projectId).orElseThrow(AdminNotFoundException::new);
      UUID groupId = (UUID) project.get("group_id");

      Boolean allowed = jdbc.queryForObject("select app_can_write_group(?)", Boolean.class, groupId);
      if (!Boolean.TRUE.equals(allowed)) {
        throw new AdminNotFoundException();
      }

      String role = normalizeRole(req.role(), "member");
      adminRepository.upsertProjectMember(projectId, req.userId(), role);

      writeAudit(httpReq, principal.userId(), groupId, "member_add", "project", projectId,
          toJson(Map.of(
              "objectType", "project",
              "objectId", projectId,
              "userId", req.userId(),
              "role", role
          ))
      );

      writeOutbox(groupId, projectId, null, principal.userId(),
          "Member.Changed",
          "Member.Changed:project:" + projectId + ":user:" + req.userId() + ":v1",
          toJson(Map.of(
              "objectType", "project",
              "objectId", projectId,
              "userId", req.userId(),
              "role", role
          ))
      );
    });
  }

  public void addCaseMember(AuthPrincipal principal,
                            UUID caseId,
                            AdminAddMemberRequest req,
                            HttpServletRequest httpReq) {
    tx.run(principal, () -> {
      var theCase = adminRepository.findCase(caseId).orElseThrow(AdminNotFoundException::new);
      UUID groupId = (UUID) theCase.get("group_id");
      UUID projectId = (UUID) theCase.get("project_id");

      Boolean allowed = jdbc.queryForObject("select app_can_write_group(?)", Boolean.class, groupId);
      if (!Boolean.TRUE.equals(allowed)) {
        throw new AdminNotFoundException();
      }

      String role = normalizeRole(req.role(), "assignee");
      adminRepository.upsertCaseMember(caseId, req.userId(), role);

      writeAudit(httpReq, principal.userId(), groupId, "member_add", "case", caseId,
          toJson(Map.of(
              "objectType", "case",
              "objectId", caseId,
              "userId", req.userId(),
              "role", role
          ))
      );

      writeOutbox(groupId, projectId, caseId, principal.userId(),
          "Member.Changed",
          "Member.Changed:case:" + caseId + ":user:" + req.userId() + ":v1",
          toJson(Map.of(
              "objectType", "case",
              "objectId", caseId,
              "userId", req.userId(),
              "role", role
          ))
      );
    });
  }

  public List<AdminProjectListItem> listProjects(AuthPrincipal principal, UUID groupId) {
    return tx.execute(principal, () -> {
      Boolean allowed = jdbc.queryForObject("select app_can_write_group(?)", Boolean.class, groupId);
      if (!Boolean.TRUE.equals(allowed)) {
        throw new AdminNotFoundException();
      }
      return adminRepository.listProjects(groupId);
    });
  }

  public List<AdminCaseListItem> listCases(AuthPrincipal principal, UUID projectId) {
    return tx.execute(principal, () -> adminRepository.listCases(projectId));
  }

  private String normalizeRole(String role, String defaultRole) {
    if (role == null || role.isBlank()) return defaultRole;
    return role.trim();
  }

  private String toJson(Map<String, Object> map) {
    // Avoid bringing internal DTOs into /admin response; JSON is used only for audit/outbox payloads.
    // Keep it stable and minimal.
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    boolean first = true;
    for (var e : map.entrySet()) {
      if (!first) sb.append(',');
      first = false;
      sb.append('"').append(escapeJson(e.getKey())).append('"').append(':');
      Object v = e.getValue();
      if (v == null) {
        sb.append("null");
      } else if (v instanceof Number || v instanceof Boolean) {
        sb.append(v);
      } else {
        sb.append('"').append(escapeJson(String.valueOf(v))).append('"');
      }
    }
    sb.append('}');
    return sb.toString();
  }

  private String escapeJson(String s) {
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
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
