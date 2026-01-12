package com.secp.api.instruction;

import com.secp.api.infra.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class InstructionExceptionHandler {

  @ExceptionHandler(InstructionNotFoundException.class)
  public ResponseEntity<ErrorResponse> notFound() {
    return ResponseEntity.status(404).body(ErrorResponse.of("NOT_FOUND", "资源不存在或无权限访问。"));
  }

  @ExceptionHandler(InstructionConflictException.class)
  public ResponseEntity<ErrorResponse> conflict(InstructionConflictException ex) {
    return ResponseEntity.status(409).body(ErrorResponse.of("CONFLICT", "状态冲突，请刷新后重试。", ex.getMessage()));
  }

  @ExceptionHandler(UnprocessableEntityException.class)
  public ResponseEntity<ErrorResponse> unprocessable(UnprocessableEntityException ex) {
    return ResponseEntity.status(422).body(ErrorResponse.of("UNPROCESSABLE_ENTITY", "请求参数不合法。", ex.getMessage()));
  }
}
