package com.secp.api.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthGlobalExceptionHandler {

  @ExceptionHandler(UnauthorizedException.class)
  public ResponseEntity<String> unauthorized() {
    return ResponseEntity.status(401).body("{\"error\":\"UNAUTHORIZED\"}");
  }
}
