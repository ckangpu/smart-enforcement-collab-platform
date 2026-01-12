package com.secp.api.auth;

import com.secp.api.infra.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ErrorResponse> state(IllegalStateException ex) {
    if ("SMS_COOLDOWN".equals(ex.getMessage())) {
      return ResponseEntity.status(429).body(ErrorResponse.of("SMS_COOLDOWN", "发送过于频繁，请稍后再试。"));
    }
    if ("SMS_DAILY_LIMIT".equals(ex.getMessage())) {
      return ResponseEntity.status(429).body(ErrorResponse.of("SMS_DAILY_LIMIT", "今日验证码发送次数已达上限，请明天再试。"));
    }
    return ResponseEntity.badRequest().body(ErrorResponse.of("BAD_REQUEST", "请求不合法。"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> arg(IllegalArgumentException ex) {
    if ("INVALID_CODE".equals(ex.getMessage())) {
      return ResponseEntity.status(401).body(ErrorResponse.of("INVALID_CODE", "验证码错误。"));
    }
    if ("INVALID_CREDENTIALS".equals(ex.getMessage())) {
      return ResponseEntity.status(401).body(ErrorResponse.of("INVALID_CREDENTIALS", "用户名或密码错误。"));
    }
    return ResponseEntity.badRequest().body(ErrorResponse.of("BAD_REQUEST", "请求不合法。"));
  }
}
