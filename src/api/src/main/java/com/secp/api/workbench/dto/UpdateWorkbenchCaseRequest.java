package com.secp.api.workbench.dto;

import java.util.UUID;
import java.time.LocalDate;

public record UpdateWorkbenchCaseRequest(
    UUID creditorId,
    UUID debtorId,
    String cause,
    String basisDocType,
    String basisDocNo,
    String basisOrg,
    String basisMainText,
    LocalDate basisDecidedAt
) {
}
