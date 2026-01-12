package com.secp.api.workbench.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.UUID;

public record CreateClueRequest(
    String codeSource,
    String manualXsCode,

    @NotBlank String category,
    @NotBlank String detail,
    String source,
    LocalDate collectedAt,
    UUID collectorUserId
) {
}
