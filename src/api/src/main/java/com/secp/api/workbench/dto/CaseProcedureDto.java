package com.secp.api.workbench.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CaseProcedureDto(
    UUID procedureId,
    String name,
    String docNo,
    String org,
    LocalDate decidedAt,
    Instant createdAt,
    Instant updatedAt
) {
}
