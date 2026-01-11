package com.secp.api.client.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ClientPaymentDto(
    UUID paymentId,
    OffsetDateTime paidAt,
    BigDecimal amount,
    String payChannel,
    String payerName,
    String bankLast4,
    String clientNote,
    boolean correctedFlag
) {
}
