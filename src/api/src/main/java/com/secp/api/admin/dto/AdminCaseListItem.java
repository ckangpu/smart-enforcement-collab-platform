package com.secp.api.admin.dto;

import java.util.UUID;

public record AdminCaseListItem(
    UUID caseId,
    UUID projectId,
    UUID groupId,
    String title
) {}
