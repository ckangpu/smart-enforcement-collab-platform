package com.secp.api.instruction.dto;

import java.util.UUID;

public record IssueInstructionRequest(
    UUID targetCaseId
) {
}
