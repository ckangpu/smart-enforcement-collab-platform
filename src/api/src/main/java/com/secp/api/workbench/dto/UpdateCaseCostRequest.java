package com.secp.api.workbench.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateCaseCostRequest(
    String category,
    BigDecimal amount,
    LocalDate occurredAt,
    String payer,
    String note
) {
}
