package com.secp.api.workbench.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record CreateWorkbenchCaseRequest(
    String codeSource,
    String manualCaseCode,

    @NotNull UUID creditorId,
    @NotNull UUID debtorId,

    @NotBlank String cause,

    String basisDocType,
    String basisDocNo,
    String basisOrg,
    LocalDate basisDecidedAt,
    String basisMainText
) {
}
