package com.secp.api.me;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.UUID;

public record MeProjectDto(
    UUID projectId,
    String projectCode,
    String name,
    String status,
    UUID groupId,
    String entrustor,
    String progressStatus,
    LocalDate targetDate,
    LocalDate acceptedAt,
    UUID ownerUserId,
    String ownerName,
    UUID leadUserId,
    String leadName,
    UUID assistUserId,
    String assistName,
    OffsetDateTime updatedAt,
    UUID latestCaseId,
    String latestCaseCode,
    String latestCaseTitle
) {
}
