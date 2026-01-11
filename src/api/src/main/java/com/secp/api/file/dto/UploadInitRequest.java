package com.secp.api.file.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UploadInitRequest(
    @NotNull UUID caseId,
    @NotBlank String filename,
    @NotBlank String contentType,
    Long sizeBytes
) {
}
