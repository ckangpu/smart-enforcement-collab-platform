package com.secp.api.instruction.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record InstructionDetailResponse(
    UUID instructionId,
    UUID groupId,
    String refType,
    UUID refId,
    String title,
    String status,
    int version,
    UUID issuedByUserId,
    OffsetDateTime issuedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<InstructionItemDetailDto> items
) {

  public record InstructionItemDetailDto(
      UUID instructionItemId,
      String title,
      String status,
      OffsetDateTime dueAt,
      UUID assigneeUserId,
      UUID doneByUserId,
      OffsetDateTime doneAt,
      UUID taskId,
      String taskStatus,
      OffsetDateTime taskPlanEnd
  ) {}
}
