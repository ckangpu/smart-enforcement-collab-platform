package com.secp.api.client.dto;

import jakarta.validation.constraints.NotBlank;

public record CreatePaymentDisputeRequest(
    @NotBlank String title,
    String message
) {
}
