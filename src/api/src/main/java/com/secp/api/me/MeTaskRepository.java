package com.secp.api.me;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class MeTaskRepository {

  private final JdbcTemplate jdbc;

  public MeTaskRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<MeTaskDto> list(UUID userId,
                             String status,
                             Boolean overdueOnly,
                             UUID projectId,
                             UUID caseId) {

    StringBuilder sql = new StringBuilder(
        "select id, title, status, priority, plan_end, case_id, project_id, instruction_item_id " +
            "from task where assignee_user_id = ?"
    );

    List<Object> args = new ArrayList<>();
    args.add(userId);

    if (status != null && !status.isBlank()) {
      sql.append(" and status = ?");
      args.add(status);
    }

    if (projectId != null) {
      sql.append(" and project_id = ?");
      args.add(projectId);
    }

    if (caseId != null) {
      sql.append(" and case_id = ?");
      args.add(caseId);
    }

    if (Boolean.TRUE.equals(overdueOnly)) {
      sql.append(" and plan_end is not null and plan_end < now()");
    }

    sql.append(" order by plan_end asc nulls last, created_at desc");

    RowMapper<MeTaskDto> mapper = (rs, rowNum) -> new MeTaskDto(
        UUID.fromString(rs.getString("id")),
        rs.getString("title"),
        rs.getString("status"),
        rs.getString("priority"),
        rs.getObject("plan_end", OffsetDateTime.class),
        rs.getObject("case_id", UUID.class),
        rs.getObject("project_id", UUID.class),
        rs.getObject("instruction_item_id", UUID.class)
    );

    return jdbc.query(sql.toString(), mapper, args.toArray());
  }
}
