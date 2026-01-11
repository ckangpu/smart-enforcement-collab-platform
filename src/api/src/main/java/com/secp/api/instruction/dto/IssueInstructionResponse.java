package com.secp.api.instruction.dto;

import java.util.List;
import java.util.UUID;

public record IssueInstructionResponse(
    UUID instructionId,
    int version,
    List<UUID> taskIds
) {
}
