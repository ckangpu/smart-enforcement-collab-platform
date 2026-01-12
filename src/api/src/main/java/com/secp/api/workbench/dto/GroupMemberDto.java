package com.secp.api.workbench.dto;

import java.util.UUID;

public record GroupMemberDto(
    UUID userId,
    String username,
    String phone
) {
}
