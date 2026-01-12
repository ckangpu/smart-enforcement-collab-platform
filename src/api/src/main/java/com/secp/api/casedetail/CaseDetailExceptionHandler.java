package com.secp.api.casedetail;

import com.secp.api.infra.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CaseDetailExceptionHandler {

  @ExceptionHandler(CaseNotFoundException.class)
  public ResponseEntity<ErrorResponse> notFound(CaseNotFoundException ex) {
    return ResponseEntity.status(404).body(ErrorResponse.of("NOT_FOUND", "案件不存在或无权限访问。"));
  }
}
