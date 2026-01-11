package com.secp.api.task.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TaskDetailResponse(
    UUID taskId,
    UUID groupId,
    UUID projectId,
    UUID caseId,
    String title,
    String status,
    String priority,
    OffsetDateTime planEnd,
    UUID assigneeUserId,
    UUID createdByUserId,
    UUID instructionItemId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
