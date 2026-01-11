package com.secp.api.report;

public record ZoneDashboardOverdueDto(
    String dayKey,
    long dailySentToday,
    long escalateSentToday
) {
}
