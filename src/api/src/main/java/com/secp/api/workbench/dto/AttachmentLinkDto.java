package com.secp.api.workbench.dto;

import java.time.Instant;
import java.util.UUID;

public record AttachmentLinkDto(
    UUID attachmentId,
    String objectType,
    UUID objectId,
    UUID fileId,
    String title,
    Instant createdAt,
    String filename,
    String contentType,
    long sizeBytes
) {
}
