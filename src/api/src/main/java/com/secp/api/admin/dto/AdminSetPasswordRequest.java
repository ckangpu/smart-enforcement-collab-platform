package com.secp.api.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminSetPasswordRequest(@NotBlank String password) {}
