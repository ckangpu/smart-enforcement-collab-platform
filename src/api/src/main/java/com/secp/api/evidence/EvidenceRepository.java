package com.secp.api.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EvidenceRepository {

  private final JdbcTemplate jdbc;

  public EvidenceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Map<String, Object>> findProject(UUID projectId) {
    List<Map<String, Object>> rows = jdbc.queryForList("select id, group_id from project where id = ?", projectId);
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(rows.getFirst());
  }

  public boolean caseBelongsToProject(UUID caseId, UUID projectId) {
    Integer n = jdbc.queryForObject(
        "select count(1) from \"case\" where id = ? and project_id = ?",
        Integer.class,
        caseId,
        projectId
    );
    return n != null && n > 0;
  }

  public boolean fileBelongsToProject(UUID fileId, UUID projectId) {
    Integer n = jdbc.queryForObject(
        "select count(1) from file_store where id = ? and project_id = ?",
        Integer.class,
        fileId,
        projectId
    );
    return n != null && n > 0;
  }

  public void insertEvidence(UUID id,
                             UUID groupId,
                             UUID projectId,
                             UUID caseId,
                             String title,
                             UUID fileId,
                             UUID createdBy) {
    jdbc.update(
        """
        insert into evidence(id, group_id, project_id, case_id, title, file_id, created_by)
        values (?,?,?,?,?,?,?)
        """,
        id, groupId, projectId, caseId, title, fileId, createdBy
    );
  }
}
