package com.secp.api.report;

public record ZoneDashboardInstructionDto(
    long itemTotal,
    long itemDone,
    Double itemDoneRate,
    long itemOverdueCurrent
) {
}
