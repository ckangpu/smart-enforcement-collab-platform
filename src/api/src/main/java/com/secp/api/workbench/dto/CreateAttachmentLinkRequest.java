package com.secp.api.workbench.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateAttachmentLinkRequest(
    @NotBlank String objectType,
    @NotNull UUID objectId,
    @NotNull UUID fileId,
    String title
) {
}
