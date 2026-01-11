package com.secp.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record SendSmsRequest(@NotBlank String phone) {}
