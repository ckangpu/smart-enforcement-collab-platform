package com.secp.api.instruction.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateInstructionItemStatusRequest(
    @NotBlank String status
) {
}
