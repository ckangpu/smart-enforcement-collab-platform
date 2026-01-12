package com.secp.api.workbench.dto;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateClueRequest(
    String category,
    String detail,
    String source,
    LocalDate collectedAt,
    UUID collectorUserId
) {
}
