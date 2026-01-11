package com.secp.api.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PaymentRepository {

  private final JdbcTemplate jdbc;

  public record PaymentRow(
      UUID id,
      UUID caseId,
      BigDecimal amount,
      OffsetDateTime paidAt,
      String payChannel,
      UUID correctedFromPaymentId,
      OffsetDateTime createdAt
  ) {
  }

  /**
   * “有效 payment”规则：只返回没有被后续更正覆盖的 payment。
   */
  public List<PaymentRow> listEffectiveByCase(UUID caseId) {
    return jdbc.query(
        """
            select p.id, p.case_id, p.amount, p.paid_at, p.pay_channel, p.corrected_from_payment_id, p.created_at
            from payment p
            where p.case_id = ?
              and not exists (
                select 1 from payment p2
                where p2.corrected_from_payment_id = p.id
              )
            order by p.created_at desc
            """,
        (rs, rowNum) -> new PaymentRow(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("case_id")),
            rs.getBigDecimal("amount"),
            rs.getObject("paid_at", OffsetDateTime.class),
            rs.getString("pay_channel"),
            rs.getObject("corrected_from_payment_id") == null ? null : UUID.fromString(rs.getString("corrected_from_payment_id")),
            rs.getObject("created_at", OffsetDateTime.class)
        ),
        caseId
    );
  }
}
