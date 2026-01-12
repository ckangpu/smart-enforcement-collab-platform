package com.secp.api.workbench.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record WorkbenchCaseDto(
    UUID caseId,
    String caseCode,
    String name,
    String court,
    String status,
    UUID creditorId,
    String creditorCode,
    String creditorName,
    UUID debtorId,
    String debtorCode,
    String debtorName,
    String cause,
    String basisDocType,
    String basisDocNo,
    String basisOrg,
    String basisMainText,
    LocalDate basisDecidedAt,
    LocalDate acceptedAt,
    Instant updatedAt
) {
}
