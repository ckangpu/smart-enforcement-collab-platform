package com.secp.api.workbench.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record WorkbenchProjectBaseDto(
    UUID projectId,
    UUID groupId,
    String code,
    String name,
    String status,
    LocalDate acceptedAt,
    String entrustor,
    String progressStatus,
    LocalDate targetDate,
    String note,
    Instant createdAt,
    Instant updatedAt,
    UUID ownerUserId,
    String ownerName,
    UUID leadUserId,
    String leadName,
    UUID assistUserId,
    String assistName
) {
  public static WorkbenchProjectBaseDto fromRow(Map<String, Object> r) {
    return new WorkbenchProjectBaseDto(
        (UUID) r.get("project_id"),
        (UUID) r.get("group_id"),
        String.valueOf(r.get("code")),
        String.valueOf(r.get("name")),
        String.valueOf(r.get("status")),
        r.get("accepted_at") == null ? null : ((java.sql.Date) r.get("accepted_at")).toLocalDate(),
        (String) r.get("entrustor"),
        (String) r.get("progress_status"),
        r.get("target_date") == null ? null : ((java.sql.Date) r.get("target_date")).toLocalDate(),
        (String) r.get("note"),
        ((java.sql.Timestamp) r.get("created_at")).toInstant(),
        ((java.sql.Timestamp) r.get("updated_at")).toInstant(),
        (UUID) r.get("owner_user_id"),
        (String) r.get("owner_name"),
        (UUID) r.get("lead_user_id"),
        (String) r.get("lead_name"),
        (UUID) r.get("assist_user_id"),
        (String) r.get("assist_name")
    );
  }
}
