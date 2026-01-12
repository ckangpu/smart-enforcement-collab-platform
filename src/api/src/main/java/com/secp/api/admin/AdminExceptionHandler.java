package com.secp.api.admin;

import com.secp.api.infra.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AdminExceptionHandler {

  @ExceptionHandler(AdminForbiddenException.class)
  public ResponseEntity<ErrorResponse> forbidden(AdminForbiddenException ex) {
    // Do not leak existence for internal admin resources.
    return ResponseEntity.status(404).body(ErrorResponse.of("NOT_FOUND", "资源不存在或无权限访问。"));
  }

  @ExceptionHandler(AdminNotFoundException.class)
  public ResponseEntity<ErrorResponse> notFound(AdminNotFoundException ex) {
    return ResponseEntity.status(404).body(ErrorResponse.of("NOT_FOUND", "资源不存在或已被删除。"));
  }
}
