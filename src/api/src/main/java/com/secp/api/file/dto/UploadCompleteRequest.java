package com.secp.api.file.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UploadCompleteRequest(
    @NotNull UUID fileId,
    @NotNull UUID caseId,
    @NotBlank String filename,
    @NotBlank String contentType,
    Long sizeBytes,
    String sha256,
    @NotBlank String s3KeyRaw
) {
}
