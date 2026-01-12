package com.secp.api.workbench.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record CreateWorkbenchProjectRequest(
    @NotNull UUID groupId,
    @NotBlank String name,
    @NotNull LocalDate acceptedAt,
    String codeSource,
    String manualCode,

    String entrustor,
    String progressStatus,
    LocalDate targetDate,
    UUID ownerUserId,
    UUID leadUserId,
    UUID assistUserId,
    String note
) {

  public String progressStatusOrDefault() {
    String v = progressStatus == null ? "" : progressStatus.trim();
    if (v.isBlank()) return "NOT_STARTED";
    return v.toUpperCase();
  }
}
