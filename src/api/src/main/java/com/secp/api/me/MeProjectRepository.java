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
        rs.getString("name"),
        rs.getString("status"),
        rs.getObject("group_id", UUID.class),
        rs.getObject("updated_at", OffsetDateTime.class)
    );

    return jdbc.query(
        "select id, name, status, group_id, updated_at from project order by updated_at desc",
        mapper
    );
  }
}
