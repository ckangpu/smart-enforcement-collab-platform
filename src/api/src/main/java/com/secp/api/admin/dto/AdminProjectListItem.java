package com.secp.api.admin.dto;

import java.util.UUID;

public record AdminProjectListItem(
    UUID projectId,
    UUID groupId,
    String name
) {}
