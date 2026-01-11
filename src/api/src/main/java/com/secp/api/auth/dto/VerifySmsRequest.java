package com.secp.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifySmsRequest(@NotBlank String phone, @NotBlank String code) {}
