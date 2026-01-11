package com.secp.api.report;

import java.math.BigDecimal;
import java.util.UUID;

public record ZoneDashboardRowDto(
    UUID groupId,
    String groupName,
    long projectCount,
    long caseCount,
    long taskCount,
    long overdueTaskCount,
    Double overdueRate,
    int missingCount,
    BigDecimal effectivePaymentSum,
    ZoneDashboardInstructionDto instruction,
    ZoneDashboardOverdueDto overdue,
    ZoneDashboardTaskDto task,
    ZoneDashboardPaymentDto payment
) {
}
