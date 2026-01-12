package com.secp.api.workbench.dto;

import java.time.LocalDate;

public record UpdateCaseMeasureRequest(
    String name,
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
