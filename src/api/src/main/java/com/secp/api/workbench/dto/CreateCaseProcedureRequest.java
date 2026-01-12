package com.secp.api.workbench.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record CreateCaseProcedureRequest(
    @NotBlank String name,
    String docNo,
    String org,
    LocalDate decidedAt
) {
}
