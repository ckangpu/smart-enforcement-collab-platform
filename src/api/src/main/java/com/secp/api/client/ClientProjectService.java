package com.secp.api.client;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.client.dto.ClientPaymentDto;
import com.secp.api.client.dto.ClientProjectDto;
import com.secp.api.infra.tx.TransactionalExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClientProjectService {

  private final TransactionalExecutor tx;
  private final JdbcTemplate jdbc;

  public List<ClientProjectDto> listMyProjects(AuthPrincipal client) {
    return tx.execute(client, () -> jdbc.query(
        """
            select p.id, p.name, p.status, p.created_at
            from project p
            join app_user u on u.id = ?
            where u.user_type = 'client'
              and u.customer_id is not null
              and p.customer_id = u.customer_id
            order by p.created_at desc
            """,
        (rs, rowNum) -> new ClientProjectDto(
            UUID.fromString(rs.getString("id")),
            rs.getString("name"),
            rs.getString("status"),
            rs.getObject("created_at", java.time.OffsetDateTime.class)
        ),
        client.userId()
    ));
  }

  /**
   * Client payment list: effective payments only; voucher_file_id never returned.
   */
  public List<ClientPaymentDto> listProjectPayments(AuthPrincipal client, UUID projectId, UUID caseId) {
    return tx.execute(client, () -> {
      if (!projectAccessible(client, projectId)) {
        throw new ClientNotFoundException();
      }
      if (caseId != null && !caseAccessibleWithinProject(client, projectId, caseId)) {
        throw new ClientNotFoundException();
      }

      String sql = """
          select p.id as payment_id,
                 p.paid_at,
                 p.amount,
                 p.pay_channel,
                 p.payer_name,
                 p.bank_last4,
                 p.client_note,
                 (p.corrected_from_payment_id is not null) as corrected_flag
          from payment p
          join project pr on pr.id = p.project_id
          join app_user u on u.id = ?
          where u.user_type = 'client'
            and u.customer_id is not null
            and pr.customer_id = u.customer_id
            and pr.id = ?
            and p.is_client_visible = true
            and not exists (
              select 1 from payment p2
              where p2.corrected_from_payment_id = p.id
            )
          """;

      Object[] args;
      if (caseId != null) {
        sql += " and p.case_id = ?";
        args = new Object[]{client.userId(), projectId, caseId};
      } else {
        args = new Object[]{client.userId(), projectId};
      }
      sql += " order by p.paid_at desc";

      return jdbc.query(
          sql,
          (rs, rowNum) -> new ClientPaymentDto(
              UUID.fromString(rs.getString("payment_id")),
              rs.getObject("paid_at", java.time.OffsetDateTime.class),
              rs.getBigDecimal("amount"),
              rs.getString("pay_channel"),
              maskPayerName(rs.getString("payer_name")),
              rs.getString("bank_last4"),
              rs.getString("client_note"),
              rs.getBoolean("corrected_flag")
          ),
          args
      );
    });
  }

  private boolean projectAccessible(AuthPrincipal client, UUID projectId) {
    Integer cnt = jdbc.queryForObject(
        """
            select count(1)
            from project p
            join app_user u on u.id = ?
            where u.user_type = 'client'
              and u.customer_id is not null
              and p.customer_id = u.customer_id
              and p.id = ?
            """,
        Integer.class,
        client.userId(),
        projectId
    );
    return cnt != null && cnt > 0;
  }

  private boolean caseAccessibleWithinProject(AuthPrincipal client, UUID projectId, UUID caseId) {
    Integer cnt = jdbc.queryForObject(
        """
            select count(1)
            from "case" c
            join project p on p.id = c.project_id
            join app_user u on u.id = ?
            where u.user_type = 'client'
              and u.customer_id is not null
              and p.customer_id = u.customer_id
              and p.id = ?
              and c.id = ?
            """,
        Integer.class,
        client.userId(),
        projectId,
        caseId
    );
    return cnt != null && cnt > 0;
  }

  private String maskPayerName(String payerName) {
    if (payerName == null || payerName.isBlank()) {
      return "";
    }
    String trimmed = payerName.trim();
    if (trimmed.length() == 1) {
      return "*";
    }
    return trimmed.substring(0, 1) + "*".repeat(Math.max(1, trimmed.length() - 1));
  }
}
