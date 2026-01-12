package com.secp.api.workbench.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateCreditorRequest(
    String codeSource,
    String manualSrCode,

    @NotBlank String name,
    String idNo,
    String unifiedCode,
    String regAddress,
    String eDeliveryPhone,
    String mailAddress,
    String mailRecipient,
    String mailPhone,
    String note
) {
}
