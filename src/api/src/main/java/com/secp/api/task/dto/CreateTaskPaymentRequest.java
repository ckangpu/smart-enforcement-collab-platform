package com.secp.api.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CreateTaskPaymentRequest(
    @NotNull BigDecimal amount,
    @NotNull OffsetDateTime paidAt,
    @NotBlank String payChannel,
    @NotBlank String payerName,
    String bankLast4,
    String clientNote,
    String internalNote,
    Boolean isClientVisible
) {
}
