package com.secp.api.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AdminMemberUpsertRequest(
    @NotNull UUID userId,
    String memberRole
) {}
