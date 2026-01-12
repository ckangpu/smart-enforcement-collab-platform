package com.secp.api.workbench.dto;

import java.util.List;

public record WorkbenchProjectDetailDto(
    WorkbenchProjectBaseDto project,
    List<WorkbenchCreditorDto> creditors,
    List<WorkbenchDebtorDto> debtors,
    List<WorkbenchCaseDto> cases
) {
}
