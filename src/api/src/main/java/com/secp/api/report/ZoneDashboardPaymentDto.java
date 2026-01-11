package com.secp.api.report;

import java.math.BigDecimal;

public record ZoneDashboardPaymentDto(
    BigDecimal sum30d,
    BigDecimal ratioTarget,
    BigDecimal ratioMandate,
    long missingDenominatorProjectCount
) {
}
