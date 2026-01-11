package com.secp.api.report;

import java.util.List;

public record ZoneDashboardTaskDto(
    List<ZoneDashboardStatusCountDto> statusCounts,
    long projectOnlyCount
) {
}
