package com.secp.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record AdminUpsertProjectRequest(
    UUID projectId,
    @NotNull UUID groupId,
    @NotBlank String name,
    BigDecimal executionTargetAmount,
    BigDecimal mandateAmount
) {}
