package com.secp.api.instruction.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

public record CreateInstructionItemRequest(
    @NotBlank String title,
    OffsetDateTime dueAt
) {
}
