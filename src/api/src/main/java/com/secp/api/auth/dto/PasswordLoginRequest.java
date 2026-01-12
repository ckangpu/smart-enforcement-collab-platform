package com.secp.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordLoginRequest(
    @NotBlank String username,
    @NotBlank String password
) {}
