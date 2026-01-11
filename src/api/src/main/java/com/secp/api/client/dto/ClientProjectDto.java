package com.secp.api.client.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ClientProjectDto(
    UUID id,
    String name,
    String status,
    OffsetDateTime createdAt
) {
}
