package com.secp.api.workbench.dto;

import java.util.UUID;

public record GroupOptionDto(
    UUID groupId,
    String groupName
) {
}
