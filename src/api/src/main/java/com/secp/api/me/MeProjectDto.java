package com.secp.api.me;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MeProjectDto(
    UUID projectId,
    String name,
    String status,
    UUID groupId,
    OffsetDateTime updatedAt
) {
}
