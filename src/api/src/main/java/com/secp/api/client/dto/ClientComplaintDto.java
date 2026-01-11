package com.secp.api.client.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ClientComplaintDto(
    UUID id,
    UUID projectId,
    UUID paymentId,
    String status,
    String title,
    String message,
    OffsetDateTime createdAt
) {
}
