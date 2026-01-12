package com.secp.api.project;

import com.secp.api.infra.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ProjectExceptionHandler {

  @ExceptionHandler(ProjectNotFoundException.class)
  public ResponseEntity<ErrorResponse> notFound(ProjectNotFoundException ex) {
    return ResponseEntity.status(404).body(ErrorResponse.of("NOT_FOUND", "项目不存在或无权限访问。"));
  }
}
