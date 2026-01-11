package com.secp.api.instruction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InstructionRepository {

  private final JdbcTemplate jdbc;

  public InstructionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<UUID> findGroupIdByProjectId(UUID projectId) {
    List<Map<String, Object>> rows = jdbc.queryForList("select group_id from project where id = ?", projectId);
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of((UUID) rows.getFirst().get("group_id"));
  }

  public Optional<Map<String, Object>> findCase(UUID caseId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "select id, group_id, project_id from \"case\" where id = ?",
        caseId
    );
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(rows.getFirst());
  }

  public Optional<UUID> findCaseIdInProject(UUID caseId, UUID projectId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "select id from \"case\" where id = ? and project_id = ?",
        caseId, projectId
    );
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of((UUID) rows.getFirst().get("id"));
  }

  public void insertInstruction(UUID id,
                               UUID groupId,
                               String refType,
                               UUID refId,
                               String title,
                               UUID createdBy) {
    jdbc.update(
        """
        insert into instruction(id, group_id, ref_type, ref_id, title, status, version, created_by)
        values (?,?,?,?,?, 'DRAFT', 0, ?)
        """,
        id, groupId, refType, refId, title, createdBy
    );
  }

  public void insertInstructionItem(UUID id,
                                   UUID instructionId,
                                   UUID groupId,
                                   String title,
                                   OffsetDateTime dueAt,
                                   UUID createdBy) {
    jdbc.update(
        """
        insert into instruction_item(id, instruction_id, group_id, title, due_at, assignee_user_id, created_by)
        values (?,?,?,?,?,?,?)
        """,
        id, instructionId, groupId, title, dueAt, createdBy, createdBy
    );
  }

  public record InstructionRow(UUID id, UUID groupId, String refType, UUID refId, String status, int version) {
  }

  public Optional<InstructionRow> findInstructionForUpdate(UUID instructionId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        select id, group_id, ref_type, ref_id, status, version
        from instruction
        where id = ?
        for update
        """,
        instructionId
    );
    if (rows.isEmpty()) return Optional.empty();
    Map<String, Object> r = rows.getFirst();
    return Optional.of(new InstructionRow(
        (UUID) r.get("id"),
        (UUID) r.get("group_id"),
        String.valueOf(r.get("ref_type")),
        (UUID) r.get("ref_id"),
        String.valueOf(r.get("status")),
        ((Number) r.get("version")).intValue()
    ));
  }

  public int issueInstruction(UUID instructionId, UUID issuedBy) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        update instruction
           set status='ISSUED',
               version = version + 1,
               issued_by = ?,
               issued_at = now(),
               updated_at = now()
         where id = ?
         returning version
        """,
        issuedBy, instructionId
    );
    if (rows.isEmpty()) {
      throw new InstructionNotFoundException();
    }
    return ((Number) rows.getFirst().get("version")).intValue();
  }

  public record InstructionItemRow(UUID id, String title, OffsetDateTime dueAt) {
  }

  public List<InstructionItemRow> listItems(UUID instructionId) {
    return jdbc.query(
        "select id, title, due_at from instruction_item where instruction_id = ? order by created_at",
        (rs, rowNum) -> new InstructionItemRow(
            UUID.fromString(rs.getString("id")),
            rs.getString("title"),
            rs.getObject("due_at", OffsetDateTime.class)
        ),
        instructionId
    );
  }

  public record UpdatedItemRow(UUID id, UUID instructionId, UUID groupId, String status) {
  }

  public Optional<UpdatedItemRow> updateItemStatus(UUID itemId, String newStatus, UUID actor) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        update instruction_item
           set status = ?,
               done_by = case when ? = 'DONE' then ? else done_by end,
               done_at = case when ? = 'DONE' then now() else done_at end,
               updated_at = now()
         where id = ?
         returning id, instruction_id, group_id, status
        """,
        newStatus, newStatus, actor, newStatus, itemId
    );
    if (rows.isEmpty()) return Optional.empty();
    Map<String, Object> r = rows.getFirst();
    return Optional.of(new UpdatedItemRow(
        (UUID) r.get("id"),
        (UUID) r.get("instruction_id"),
        (UUID) r.get("group_id"),
        String.valueOf(r.get("status"))
    ));
  }
}
