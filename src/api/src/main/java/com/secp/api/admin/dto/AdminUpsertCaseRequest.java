package com.secp.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AdminUpsertCaseRequest(
    UUID caseId,
    @NotNull UUID projectId,
    @NotBlank String title
) {}
