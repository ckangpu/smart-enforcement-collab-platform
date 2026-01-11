package com.secp.api.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AdminExceptionHandler {

  @ExceptionHandler(AdminForbiddenException.class)
  public ResponseEntity<String> forbidden(AdminForbiddenException ex) {
    // Do not leak existence for internal admin resources.
    return ResponseEntity.status(404).body("{\"error\":\"NOT_FOUND\"}");
  }

  @ExceptionHandler(AdminNotFoundException.class)
  public ResponseEntity<String> notFound(AdminNotFoundException ex) {
    return ResponseEntity.status(404).body("{\"error\":\"NOT_FOUND\"}");
  }
}
