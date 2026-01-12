package com.secp.api.admin.dto;

import java.util.UUID;

public record AdminCreateProjectResponse(
    UUID projectId,
    String code,
    UUID groupId,
    String name
) {}
