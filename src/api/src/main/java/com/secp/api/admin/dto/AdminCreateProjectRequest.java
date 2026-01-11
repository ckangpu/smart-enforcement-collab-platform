package com.secp.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record AdminCreateProjectRequest(
    @NotNull UUID groupId,
    @NotBlank String name,
    List<String> bizTags,
    BigDecimal mandateAmount,
    BigDecimal executionTargetAmount
) {}
