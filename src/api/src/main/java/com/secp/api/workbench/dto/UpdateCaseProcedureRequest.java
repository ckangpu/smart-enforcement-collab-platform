package com.secp.api.workbench.dto;

import java.time.LocalDate;

public record UpdateCaseProcedureRequest(
    String name,
    String docNo,
    String org,
    LocalDate decidedAt
) {
}
