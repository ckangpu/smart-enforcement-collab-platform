package com.secp.api.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreatePaymentRequest(
    @NotNull UUID projectId,
    @NotNull UUID caseId,
    @NotNull BigDecimal amount,
    @NotNull OffsetDateTime paidAt,
    @NotBlank String payChannel,
    @NotBlank String payerName,
    String bankLast4,
    String clientNote,
    String internalNote,
    Boolean isClientVisible
) {}
