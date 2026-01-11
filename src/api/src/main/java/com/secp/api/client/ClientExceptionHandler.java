package com.secp.api.client;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ClientExceptionHandler {

  @ExceptionHandler(ClientForbiddenException.class)
  public ResponseEntity<String> forbidden(ClientForbiddenException ex) {
    return ResponseEntity.status(403).body("{\"error\":\"FORBIDDEN\",\"reason\":\"" + ex.getMessage() + "\"}");
  }

  @ExceptionHandler(ClientNotFoundException.class)
  public ResponseEntity<String> notFound(ClientNotFoundException ex) {
    return ResponseEntity.status(404).body("{\"error\":\"NOT_FOUND\"}");
  }
}
