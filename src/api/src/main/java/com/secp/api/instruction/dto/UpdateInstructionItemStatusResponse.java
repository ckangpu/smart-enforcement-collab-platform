package com.secp.api.instruction.dto;

import java.util.UUID;

public record UpdateInstructionItemStatusResponse(UUID instructionItemId, String status) {
}
