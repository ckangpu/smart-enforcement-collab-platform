package com.secp.api.task;

import com.secp.api.task.dto.TaskDetailResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TaskQueryRepository {

  private final JdbcTemplate jdbc;

  public TaskQueryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<TaskDetailResponse> getTaskDetail(UUID taskId) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        """
        select id, group_id, project_id, case_id,
               title, status, priority, plan_end,
               assignee_user_id, created_by, instruction_item_id,
               created_at, updated_at
          from task
         where id = ?
        """,
        taskId
    );
    if (rows.isEmpty()) return Optional.empty();
    Map<String, Object> r = rows.getFirst();
    return Optional.of(new TaskDetailResponse(
        (UUID) r.get("id"),
        (UUID) r.get("group_id"),
        (UUID) r.get("project_id"),
        (UUID) r.get("case_id"),
        String.valueOf(r.get("title")),
        String.valueOf(r.get("status")),
        r.get("priority") == null ? null : String.valueOf(r.get("priority")),
        (OffsetDateTime) r.get("plan_end"),
        (UUID) r.get("assignee_user_id"),
        (UUID) r.get("created_by"),
        (UUID) r.get("instruction_item_id"),
        (OffsetDateTime) r.get("created_at"),
        (OffsetDateTime) r.get("updated_at")
    ));
  }
}
