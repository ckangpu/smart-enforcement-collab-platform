package com.secp.api.casedetail.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CaseDetailResponse(
    CaseDto caze,
    ProjectDto project,
    List<MemberDto> members,
    List<TaskDto> tasks,
    List<InstructionDto> instructions,
    PaymentsSummaryDto payments
) {

  public record CaseDto(
      UUID caseId,
      String code,
      LocalDate acceptedAt,
      String title,
      String status,
      UUID groupId,
      UUID projectId,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt
  ) {}

  public record ProjectDto(
      UUID projectId,
      String code,
      String name
  ) {}

  public record MemberDto(
      UUID userId,
      String role,
      String username,
      String phoneMasked
  ) {}

  public record TaskDto(
      UUID taskId,
      String title,
      String status,
      String priority,
      OffsetDateTime dueAt,
      UUID assigneeUserId
  ) {}

  public record InstructionDto(
      UUID instructionId,
      String title,
      String status,
      int version,
      OffsetDateTime deadline,
      OffsetDateTime issuedAt,
      int itemTotal,
      int itemDone,
      int itemOverdue
  ) {}

  public record PaymentsSummaryDto(
      BigDecimal sumAll,
      BigDecimal sum30d,
      int effectiveCount,
      OffsetDateTime latestPaidAt
  ) {}
}
