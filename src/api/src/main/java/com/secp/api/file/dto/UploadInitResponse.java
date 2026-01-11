package com.secp.api.file.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UploadInitResponse(
    UUID fileId,
    String s3KeyRaw,
    String presignedPutUrl,
    OffsetDateTime expiresAt
) {
}
