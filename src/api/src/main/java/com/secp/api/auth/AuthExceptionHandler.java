package com.secp.api.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<String> state(IllegalStateException ex) {
    if ("SMS_COOLDOWN".equals(ex.getMessage())) {
      return ResponseEntity.status(429).body("{\"error\":\"SMS_COOLDOWN\"}");
    }
    if ("SMS_DAILY_LIMIT".equals(ex.getMessage())) {
      return ResponseEntity.status(429).body("{\"error\":\"SMS_DAILY_LIMIT\"}");
    }
    return ResponseEntity.badRequest().body("{\"error\":\"BAD_REQUEST\"}");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> arg(IllegalArgumentException ex) {
    if ("INVALID_CODE".equals(ex.getMessage())) {
      return ResponseEntity.status(401).body("{\"error\":\"INVALID_CODE\"}");
    }
    return ResponseEntity.badRequest().body("{\"error\":\"BAD_REQUEST\"}");
  }
}
