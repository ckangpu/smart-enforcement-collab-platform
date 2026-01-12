package com.secp.api.workbench.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record CreateCaseMeasureRequest(
    @NotBlank String name,
    String target,
    String basisOrg,
    String basisDocNo,
    String basisDocName,
    String content,
    String result,
    String rankNo,
    LocalDate dueAt,
    String note
) {
}
