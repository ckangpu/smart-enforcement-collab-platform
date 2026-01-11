package com.secp.api.instruction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.secp.api.instruction.dto.InstructionDetailResponse;

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

  public record InstructionItemState(UUID id,
                                     UUID instructionId,
                                     UUID groupId,
                                     String status,
                                     UUID assigneeUserId) {
  }

  public Optional<InstructionItemState> findItem(UUID itemId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "select id, instruction_id, group_id, status, assignee_user_id from instruction_item where id = ?",
        itemId
    );
    if (rows.isEmpty()) return Optional.empty();
    Map<String, Object> r = rows.getFirst();
    return Optional.of(new InstructionItemState(
        (UUID) r.get("id"),
        (UUID) r.get("instruction_id"),
        (UUID) r.get("group_id"),
        String.valueOf(r.get("status")),
        (UUID) r.get("assignee_user_id")
    ));
  }

  public record StatusChangedRow(UUID id,
                                 UUID instructionId,
                                 UUID groupId,
                                 String fromStatus,
                                 String toStatus,
                                 int statusVersion,
                                 UUID assigneeUserId) {
  }

  public Optional<StatusChangedRow> changeItemStatus(UUID itemId, String fromStatus, String toStatus, UUID actor) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        update instruction_item
           set status = ?,
               status_version = status_version + 1,
               done_by = case when ? = 'DONE' then ? else done_by end,
               done_at = case when ? = 'DONE' then now() else done_at end,
               updated_at = now()
         where id = ?
           and status = ?
         returning id, instruction_id, group_id, status_version, assignee_user_id
        """,
        toStatus,
        toStatus,
        actor,
        toStatus,
        itemId,
        fromStatus
    );
    if (rows.isEmpty()) return Optional.empty();
    Map<String, Object> r = rows.getFirst();
    return Optional.of(new StatusChangedRow(
        (UUID) r.get("id"),
        (UUID) r.get("instruction_id"),
        (UUID) r.get("group_id"),
        fromStatus,
        toStatus,
        ((Number) r.get("status_version")).intValue(),
        (UUID) r.get("assignee_user_id")
    ));
  }

  public Optional<UpdatedItemRow> updateItemStatus(UUID itemId, String newStatus, UUID actor) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        update instruction_item
           set status = ?,
           status_version = status_version + 1,
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

  public Optional<InstructionDetailResponse> getInstructionDetail(UUID instructionId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        select id, group_id, ref_type, ref_id, title, status, version,
               issued_by, issued_at, created_at, updated_at
          from instruction
         where id = ?
        """,
        instructionId
    );
    if (rows.isEmpty()) return Optional.empty();
    Map<String, Object> r = rows.getFirst();

    List<InstructionDetailResponse.InstructionItemDetailDto> items = jdbc.query(
        """
        select ii.id as instruction_item_id,
               ii.title as item_title,
               ii.status as item_status,
               ii.due_at,
               ii.assignee_user_id,
               ii.done_by,
               ii.done_at,
               t.id as task_id,
               t.status as task_status,
               t.plan_end as task_plan_end
          from instruction_item ii
          left join task t on t.instruction_item_id = ii.id
         where ii.instruction_id = ?
         order by ii.created_at
        """,
        (rs, rowNum) -> new InstructionDetailResponse.InstructionItemDetailDto(
            rs.getObject("instruction_item_id", UUID.class),
            rs.getString("item_title"),
            rs.getString("item_status"),
            rs.getObject("due_at", OffsetDateTime.class),
            rs.getObject("assignee_user_id", UUID.class),
            rs.getObject("done_by", UUID.class),
            rs.getObject("done_at", OffsetDateTime.class),
            rs.getObject("task_id", UUID.class),
            rs.getString("task_status"),
            rs.getObject("task_plan_end", OffsetDateTime.class)
        ),
        instructionId
    );

    return Optional.of(new InstructionDetailResponse(
        (UUID) r.get("id"),
        (UUID) r.get("group_id"),
        String.valueOf(r.get("ref_type")),
        (UUID) r.get("ref_id"),
        String.valueOf(r.get("title")),
        String.valueOf(r.get("status")),
        ((Number) r.get("version")).intValue(),
        (UUID) r.get("issued_by"),
        (OffsetDateTime) r.get("issued_at"),
        (OffsetDateTime) r.get("created_at"),
        (OffsetDateTime) r.get("updated_at"),
        items
    ));
  }
}
