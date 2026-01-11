package com.secp.api.task;

import com.secp.api.instruction.UnprocessableEntityException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TaskExceptionHandler {

  @ExceptionHandler(TaskNotFoundException.class)
  public ResponseEntity<String> notFound() {
    return ResponseEntity.status(404).body("{\"error\":\"NOT_FOUND\"}");
  }

  @ExceptionHandler(UnprocessableEntityException.class)
  public ResponseEntity<String> unprocessable(UnprocessableEntityException ex) {
    return ResponseEntity.status(422).body("{\"error\":\"UNPROCESSABLE_ENTITY\",\"reason\":\"" + ex.getMessage() + "\"}");
  }
}
