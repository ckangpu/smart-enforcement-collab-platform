package com.secp.api.workbench.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkbenchCreditorDto(
    UUID creditorId,
    String srCode,
    String name,
    String idNo,
    String unifiedCode,
    String regAddress,
    String eDeliveryPhone,
    String mailAddress,
    String mailRecipient,
    String mailPhone,
    String note,
    Instant createdAt,
    Instant updatedAt
) {
}
