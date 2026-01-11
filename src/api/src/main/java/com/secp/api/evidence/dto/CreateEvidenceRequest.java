package com.secp.api.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateEvidenceRequest(
    @NotNull UUID projectId,
    UUID caseId,
    @NotBlank String title,
    UUID fileId
) {
}
