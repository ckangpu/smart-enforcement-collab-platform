package com.secp.api.me;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MeTaskDto(
    UUID taskId,
    String title,
    String status,
    String priority,
    OffsetDateTime planEnd,
    UUID caseId,
    UUID projectId,
    UUID instructionItemId
) {
}
