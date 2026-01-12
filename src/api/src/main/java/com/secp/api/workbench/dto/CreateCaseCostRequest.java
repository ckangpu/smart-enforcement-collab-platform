package com.secp.api.workbench.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateCaseCostRequest(
    @NotBlank String category,
    @NotNull BigDecimal amount,
    LocalDate occurredAt,
    String payer,
    String note
) {
}
