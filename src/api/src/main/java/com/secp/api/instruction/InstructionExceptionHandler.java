package com.secp.api.instruction;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class InstructionExceptionHandler {

  @ExceptionHandler(InstructionNotFoundException.class)
  public ResponseEntity<String> notFound() {
    return ResponseEntity.status(404).body("{\"error\":\"NOT_FOUND\"}");
  }

  @ExceptionHandler(InstructionConflictException.class)
  public ResponseEntity<String> conflict(InstructionConflictException ex) {
    return ResponseEntity.status(409).body("{\"error\":\"CONFLICT\",\"reason\":\"" + ex.getMessage() + "\"}");
  }

  @ExceptionHandler(UnprocessableEntityException.class)
  public ResponseEntity<String> unprocessable(UnprocessableEntityException ex) {
    return ResponseEntity.status(422).body("{\"error\":\"UNPROCESSABLE_ENTITY\",\"reason\":\"" + ex.getMessage() + "\"}");
  }
}
