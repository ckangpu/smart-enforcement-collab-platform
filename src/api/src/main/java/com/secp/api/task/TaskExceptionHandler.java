package com.secp.api.task;

import com.secp.api.infra.ErrorResponse;
import com.secp.api.instruction.UnprocessableEntityException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TaskExceptionHandler {

  @ExceptionHandler(TaskNotFoundException.class)
  public ResponseEntity<ErrorResponse> notFound() {
    return ResponseEntity.status(404).body(ErrorResponse.of("NOT_FOUND", "资源不存在或无权限访问。"));
  }

  @ExceptionHandler(UnprocessableEntityException.class)
  public ResponseEntity<ErrorResponse> unprocessable(UnprocessableEntityException ex) {
    return ResponseEntity.status(422).body(ErrorResponse.of("UNPROCESSABLE_ENTITY", "请求参数不合法。", ex.getMessage()));
  }
}
