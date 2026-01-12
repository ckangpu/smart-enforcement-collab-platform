package com.secp.api.auth;

import com.secp.api.infra.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthGlobalExceptionHandler {

  @ExceptionHandler(UnauthorizedException.class)
  public ResponseEntity<ErrorResponse> unauthorized() {
    return ResponseEntity.status(401).body(ErrorResponse.of("UNAUTHORIZED", "未登录或登录已失效，请重新登录。"));
  }

  @ExceptionHandler(AuthForbiddenException.class)
  public ResponseEntity<ErrorResponse> forbidden() {
    return ResponseEntity.status(403).body(ErrorResponse.of("FORBIDDEN", "仅限内部账号访问。"));
  }
}
