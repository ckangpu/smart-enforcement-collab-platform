package com.secp.api.admin.dto;

import java.util.UUID;

public record AdminCreateCaseResponse(
    UUID caseId,
    UUID projectId,
    UUID groupId,
    String code
) {}
