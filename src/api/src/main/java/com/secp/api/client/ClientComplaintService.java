package com.secp.api.client;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.client.dto.ClientComplaintDto;
import com.secp.api.infra.tx.TransactionalExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClientComplaintService {

  private final TransactionalExecutor tx;
  private final JdbcTemplate jdbc;

  public List<ClientComplaintDto> listMyComplaints(AuthPrincipal client) {
    return tx.execute(client, () -> jdbc.query(
        """
            select c.id, c.project_id, c.payment_id, c.status, c.title, c.message, c.created_at
            from reconcile_complaint c
            join app_user u on u.id = ?
            where u.user_type = 'client'
              and u.customer_id is not null
              and c.customer_id = u.customer_id
            order by c.created_at desc
            """,
        (rs, rowNum) -> new ClientComplaintDto(
            UUID.fromString(rs.getString("id")),
            rs.getObject("project_id") == null ? null : UUID.fromString(rs.getString("project_id")),
            rs.getObject("payment_id") == null ? null : UUID.fromString(rs.getString("payment_id")),
            rs.getString("status"),
            rs.getString("title"),
            rs.getString("message"),
            rs.getObject("created_at", java.time.OffsetDateTime.class)
        ),
        client.userId()
    ));
  }
}
