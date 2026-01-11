package com.secp.api.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AdminAddMemberRequest(
    @NotNull UUID userId,
    String role
) {}
