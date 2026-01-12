package com.secp.api.workbench.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DebtorClueDto(
    UUID clueId,
    String xsCode,
    String category,
    String detail,
    String source,
    LocalDate collectedAt,
    UUID collectorUserId,
    String collectorName,
    Instant createdAt,
    Instant updatedAt
) {
}
