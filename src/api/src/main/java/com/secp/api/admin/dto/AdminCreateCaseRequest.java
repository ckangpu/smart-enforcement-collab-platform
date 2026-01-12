package com.secp.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record AdminCreateCaseRequest(
    @NotNull UUID projectId,
    @NotBlank String title,
    LocalDate acceptedAt,
    String codeSource,
    String code
) {}
