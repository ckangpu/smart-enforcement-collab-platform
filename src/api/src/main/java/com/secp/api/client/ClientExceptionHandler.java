package com.secp.api.client;

import com.secp.api.infra.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ClientExceptionHandler {

  @ExceptionHandler(ClientForbiddenException.class)
  public ResponseEntity<ErrorResponse> forbidden(ClientForbiddenException ex) {
    String reason = ex.getMessage();
    String msg = "无权限访问。";
    if ("CLIENT_ONLY".equals(reason)) {
      msg = "仅限客户账号访问。";
    }
    return ResponseEntity.status(403).body(ErrorResponse.of("FORBIDDEN", msg, reason));
  }

  @ExceptionHandler(ClientNotFoundException.class)
  public ResponseEntity<ErrorResponse> notFound(ClientNotFoundException ex) {
    return ResponseEntity.status(404).body(ErrorResponse.of("NOT_FOUND", "资源不存在或已被删除。"));
  }
}
