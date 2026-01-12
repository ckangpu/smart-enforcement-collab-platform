package com.secp.api.project.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ProjectDetailResponse(
    ProjectDto project,
    MembersDto members,
    List<CaseDto> cases,
    List<TaskDto> tasks,
    List<InstructionSummaryDto> instructions,
    PaymentsSummaryDto payments
) {

  public record ProjectDto(
      UUID projectId,
      String code,
      LocalDate acceptedAt,
      String name,
      UUID groupId,
      List<String> bizTags,
      BigDecimal mandateAmount,
      BigDecimal executionTargetAmount,
      String status,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt
  ) {
  }

  public record MembersDto(
      List<ProjectMemberDto> projectMembers,
      List<CaseMemberDto> caseMembers
  ) {
  }

  public record ProjectMemberDto(
      UUID userId,
      String role,
      String username,
      String phoneMasked
  ) {
  }

  public record CaseMemberDto(
      UUID caseId,
      UUID userId,
      String role,
      String username,
      String phoneMasked
  ) {
  }

  public record CaseDto(
      UUID caseId,
      String code,
      LocalDate acceptedAt,
      String title,
      String status,
      OffsetDateTime createdAt
  ) {
  }

  public record TaskDto(
      UUID taskId,
      String title,
      String status,
      String priority,
      UUID caseId,
      UUID instructionItemId,
      OffsetDateTime dueAt,
      UUID assigneeUserId
  ) {
  }

  public record InstructionSummaryDto(
      UUID instructionId,
      String title,
      String status,
      int version,
      OffsetDateTime deadline,
      OffsetDateTime issuedAt,
      int itemTotal,
      int itemDone
  ) {
  }

  public record PaymentsSummaryDto(
      BigDecimal sumAll,
      BigDecimal sum30d,
      int effectiveCount,
      OffsetDateTime latestPaidAt
  ) {
  }
}
