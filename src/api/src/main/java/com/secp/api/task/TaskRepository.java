package com.secp.api.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TaskRepository {

  private final JdbcTemplate jdbc;

  public TaskRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insertTask(UUID taskId,
                         UUID groupId,
                         UUID projectId,
                         UUID caseId,
                         String title,
                         UUID assigneeUserId,
                         UUID createdBy,
                         UUID instructionItemId,
                         OffsetDateTime planEnd,
                         String priority) {
    jdbc.update(
        """
        insert into task(
          id, group_id, project_id, case_id,
          title, status, priority, plan_end,
          assignee_user_id, created_by, instruction_item_id
        )
        values (?,?,?,?,?, 'TODO', ?, ?, ?, ?, ?)
        """,
        taskId, groupId, projectId, caseId,
        title,
        priority,
        planEnd,
        assigneeUserId,
        createdBy,
        instructionItemId
    );
  }

  public record TaskCore(UUID id, UUID groupId, UUID projectId, UUID caseId) {
  }

  public Optional<TaskCore> findTaskCore(UUID taskId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "select id, group_id, project_id, case_id from task where id = ?",
        taskId
    );
    if (rows.isEmpty()) return Optional.empty();
    Map<String, Object> r = rows.getFirst();
    return Optional.of(new TaskCore(
        (UUID) r.get("id"),
        (UUID) r.get("group_id"),
        (UUID) r.get("project_id"),
        (UUID) r.get("case_id")
    ));
  }
}
