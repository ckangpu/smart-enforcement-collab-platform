package com.secp.api.workbench.dto;

public record UpdateCreditorRequest(
    String name,
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
