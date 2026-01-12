package com.secp.api.me;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class MeProjectRepository {

  private final JdbcTemplate jdbc;

  public MeProjectRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<MeProjectDto> list() {
    RowMapper<MeProjectDto> mapper = (rs, rowNum) -> new MeProjectDto(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("status"),
        rs.getObject("group_id", UUID.class),
        rs.getString("entrustor"),
        rs.getString("progress_status"),
        rs.getDate("target_date") == null ? null : rs.getDate("target_date").toLocalDate(),
        rs.getDate("accepted_at") == null ? null : rs.getDate("accepted_at").toLocalDate(),
        rs.getObject("owner_user_id", UUID.class),
        rs.getString("owner_name"),
        rs.getObject("lead_user_id", UUID.class),
        rs.getString("lead_name"),
        rs.getObject("assist_user_id", UUID.class),
        rs.getString("assist_name"),
        rs.getObject("updated_at", OffsetDateTime.class),
        rs.getObject("latest_case_id", UUID.class),
        rs.getString("latest_case_code"),
        rs.getString("latest_case_title")
    );

    return jdbc.query(
        """
        select p.id,
               p.code,
               p.name,
               p.status,
               p.group_id,
               p.entrustor,
               p.progress_status,
               p.target_date,
               p.accepted_at,
               p.owner_user_id,
               ou.username as owner_name,
               p.lead_user_id,
               lu.username as lead_name,
               p.assist_user_id,
               au.username as assist_name,
               p.updated_at,
               lc.id as latest_case_id,
               lc.code as latest_case_code,
               lc.title as latest_case_title
          from project p
          left join app_user ou on ou.id = p.owner_user_id
          left join app_user lu on lu.id = p.lead_user_id
          left join app_user au on au.id = p.assist_user_id
          left join lateral (
            select c.id, c.code, c.title
              from "case" c
             where c.project_id = p.id
             order by c.created_at desc
             limit 1
          ) lc on true
         order by p.updated_at desc
        """,
        mapper
    );
  }
}
