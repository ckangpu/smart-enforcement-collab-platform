package com.secp.api.preview.dto;

import java.time.OffsetDateTime;

public record CreatePreviewTokenResponse(
    String token,
    OffsetDateTime expiresAt
) {
}
