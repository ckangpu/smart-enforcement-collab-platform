package com.secp.api.workbench.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CaseMeasureDto(
    UUID measureId,
    String name,
    String target,
    String basisOrg,
    String basisDocNo,
    String basisDocName,
    String content,
    String result,
    String rankNo,
    LocalDate dueAt,
    String note,
    Instant createdAt,
    Instant updatedAt
) {
}
