package com.secp.api.workbench;

import com.secp.api.infra.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class WorkbenchExceptionHandler {

  @ExceptionHandler(WorkbenchValidationException.class)
  public ResponseEntity<ErrorResponse> validation(WorkbenchValidationException ex) {
    int status = ex.httpStatus();
    String code = status == 409 ? "CONFLICT" : "UNPROCESSABLE_ENTITY";
    return ResponseEntity.status(status).body(ErrorResponse.of(code, ex.getMessage()));
  }
}
