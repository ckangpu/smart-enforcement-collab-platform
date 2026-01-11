package com.secp.api.auth;

import com.secp.api.infra.RlsSessionJdbc;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final JdbcTemplate jdbc;
  private final RlsSessionJdbc rlsSessionJdbc;
  private final SmsCodeService smsCodeService;
  private final JwtService jwtService;

  public void sendSms(String phone) {
    smsCodeService.sendCode(phone);
  }

  @Transactional
  public String verifyAndIssueJwt(String phone, String code) {
    if (!smsCodeService.verify(phone, code)) {
      throw new IllegalArgumentException("INVALID_CODE");
    }

    // For auth flow, use admin session (so we can lookup user + groups regardless of RLS).
    rlsSessionJdbc.applyRlsSession("", true, "");

    var userRow = jdbc.queryForMap("select id, username, is_admin from app_user where phone=?", phone);
    UUID userId = (UUID) userRow.get("id");
    String username = (String) userRow.get("username");
    boolean isAdmin = Boolean.TRUE.equals(userRow.get("is_admin"));

    // user_type must be embedded into JWT for client/internal routing
    String userType = String.valueOf(jdbc.queryForObject(
        "select user_type from app_user where id=?",
        String.class,
        userId
    ));

    AuthPrincipal principal = new AuthPrincipal(userId, isAdmin, username, userType);
    return jwtService.sign(principal);
  }
}
