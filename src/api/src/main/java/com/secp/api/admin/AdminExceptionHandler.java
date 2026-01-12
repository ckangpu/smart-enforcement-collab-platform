package com.secp.api.admin;

import com.secp.api.infra.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AdminExceptionHandler {

  @ExceptionHandler(AdminBizCodeInvalidException.class)
  public ResponseEntity<ErrorResponse> invalidBizCode(AdminBizCodeInvalidException ex) {
    return ResponseEntity.status(422).body(ErrorResponse.of("UNPROCESSABLE_ENTITY", "编号格式不正确。", ex.getMessage()));
  }

  @ExceptionHandler(AdminBizCodeConflictException.class)
  public ResponseEntity<ErrorResponse> bizCodeConflict(AdminBizCodeConflictException ex) {
    return ResponseEntity.status(409).body(ErrorResponse.of("CONFLICT", "编号已存在，请更换。"));
  }

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
