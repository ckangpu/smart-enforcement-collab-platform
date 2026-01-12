package com.secp.api.admin;

import com.secp.api.admin.dto.AdminCaseListItem;
import com.secp.api.admin.dto.AdminProjectListItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AdminRepository {

  private final JdbcTemplate jdbc;

  public AdminRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Map<String, Object>> findProject(UUID projectId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "select id, group_id from project where id=?",
        projectId
    );
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(rows.getFirst());
  }

  public Optional<Map<String, Object>> findCase(UUID caseId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "select id, group_id, project_id from \"case\" where id=?",
        caseId
    );
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(rows.getFirst());
  }

  public Optional<UUID> findProjectGroupId(UUID projectId) {
    List<Map<String, Object>> rows = jdbc.queryForList("select group_id from project where id=?", projectId);
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of((UUID) rows.getFirst().get("group_id"));
  }

  public void insertProject(UUID projectId,
                            UUID groupId,
                            String code,
                            LocalDate acceptedAt,
                            String name,
                            UUID actorUserId,
                            List<String> bizTags,
                            BigDecimal executionTargetAmount,
                            BigDecimal mandateAmount) {

    String[] tags = bizTags == null ? new String[0] : bizTags.toArray(new String[0]);

    jdbc.update(
        """
        insert into project(
          id, group_id, code, accepted_at,
          name, status, created_by,
          biz_tags, execution_target_amount, mandate_amount
        )
        values (?,?,?,?, ?, 'ACTIVE', ?, ?, ?, ?)
        """,
        projectId,
        groupId,
        code,
        acceptedAt,
        name,
        actorUserId,
        tags,
        executionTargetAmount,
        mandateAmount
    );
  }

  public void insertCase(UUID caseId,
                         UUID projectId,
                         UUID groupIdInherited,
                         String code,
                         LocalDate acceptedAt,
                         String title,
                         UUID actorUserId) {
    jdbc.update(
        """
        insert into "case"(id, group_id, project_id, code, accepted_at, title, status, created_by)
        values (?,?,?,?,?, ?, 'OPEN', ?)
        """,
        caseId,
        groupIdInherited,
        projectId,
        code,
        acceptedAt,
        title,
        actorUserId
    );
  }

  public UUID upsertProject(UUID projectId,
                           UUID groupId,
                           String code,
                           LocalDate acceptedAt,
                           String name,
                           UUID actorUserId,
                           BigDecimal executionTargetAmount,
                           BigDecimal mandateAmount) {

    UUID id = projectId != null ? projectId : UUID.randomUUID();

    jdbc.update(
        """
        insert into project(
          id, group_id, code, accepted_at,
          name, status, created_by,
          execution_target_amount, mandate_amount
        )
      values (?,?,?,?, ?, 'ACTIVE', ?, ?, ?)
        on conflict (id) do update
          set name = excluded.name,
              execution_target_amount = excluded.execution_target_amount,
              mandate_amount = excluded.mandate_amount,
              updated_at = now()
        """,
        id,
        groupId,
        code,
        acceptedAt,
        name,
        actorUserId,
        executionTargetAmount,
        mandateAmount
    );

    return id;
  }

  public UUID upsertCase(UUID caseId,
                        UUID projectId,
                        UUID groupIdInherited,
                        String code,
                        LocalDate acceptedAt,
                        String title,
                        UUID actorUserId) {

    UUID id = caseId != null ? caseId : UUID.randomUUID();

    jdbc.update(
        """
      insert into "case"(id, group_id, project_id, code, accepted_at, title, status, created_by)
      values (?,?,?,?,?, ?, 'OPEN', ?)
        on conflict (id) do update
          set title = excluded.title,
              updated_at = now()
        """,
        id,
        groupIdInherited,
        projectId,
      code,
      acceptedAt,
        title,
        actorUserId
    );

    return id;
  }

  public int upsertProjectMembers(UUID projectId, List<MemberRow> members) {
    int n = 0;
    for (MemberRow m : members) {
      jdbc.update(
          """
          insert into project_member(project_id, user_id, member_role)
          values (?,?,?)
          on conflict (project_id, user_id) do update set member_role = excluded.member_role
          """,
          projectId,
          m.userId,
          m.memberRole
      );
      n++;
    }
    return n;
  }

  public int upsertCaseMembers(UUID caseId, List<MemberRow> members) {
    int n = 0;
    for (MemberRow m : members) {
      jdbc.update(
          """
          insert into case_member(case_id, user_id, member_role)
          values (?,?,?)
          on conflict (case_id, user_id) do update set member_role = excluded.member_role
          """,
          caseId,
          m.userId,
          m.memberRole
      );
      n++;
    }
    return n;
  }

  public void upsertProjectMember(UUID projectId, UUID userId, String memberRole) {
    jdbc.update(
        """
        insert into project_member(project_id, user_id, member_role)
        values (?,?,?)
        on conflict (project_id, user_id) do update set member_role = excluded.member_role
        """,
        projectId,
        userId,
        memberRole
    );
  }

  public void upsertCaseMember(UUID caseId, UUID userId, String memberRole) {
    jdbc.update(
        """
        insert into case_member(case_id, user_id, member_role)
        values (?,?,?)
        on conflict (case_id, user_id) do update set member_role = excluded.member_role
        """,
        caseId,
        userId,
        memberRole
    );
  }

  public List<AdminProjectListItem> listProjects(UUID groupId) {
    return jdbc.query(
        "select id, group_id, name from project where group_id=? order by created_at desc limit 200",
        (rs, rowNum) -> new AdminProjectListItem(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("group_id")),
            rs.getString("name")
        ),
        groupId
    );
  }

  public List<AdminCaseListItem> listCases(UUID projectId) {
    return jdbc.query(
        "select id, project_id, group_id, title from \"case\" where project_id=? order by created_at desc limit 200",
        (rs, rowNum) -> new AdminCaseListItem(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("project_id")),
            UUID.fromString(rs.getString("group_id")),
            rs.getString("title")
        ),
        projectId
    );
  }

  public record MemberRow(UUID userId, String memberRole) {}
}
