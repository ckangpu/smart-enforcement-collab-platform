package com.secp.api.workbench.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CaseCostDto(
    UUID costId,
    String category,
    BigDecimal amount,
    LocalDate occurredAt,
    String payer,
    String note,
    Instant createdAt,
    Instant updatedAt
) {
}
