package com.secp.api.workbench.dto;

import java.util.UUID;

public record CreateWorkbenchProjectResponse(
    UUID projectId,
    UUID groupId,
    String projectCode
) {
}
