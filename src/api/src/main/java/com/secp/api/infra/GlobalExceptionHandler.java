package com.secp.api.infra;

import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> illegalArg(IllegalArgumentException ex) {
    String code = ex.getMessage() == null ? "BAD_REQUEST" : ex.getMessage();
    if ("FORBIDDEN".equals(code)) {
      return ResponseEntity.status(403).body(ErrorResponse.of("FORBIDDEN", "无权限访问。"));
    }
    return ResponseEntity.badRequest().body(ErrorResponse.of("BAD_REQUEST", "请求不合法。"));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ErrorResponse> illegalState(IllegalStateException ex) {
    String msg = ex.getMessage() == null ? "" : ex.getMessage();
    if ("PDF_RENDER_FAILED".equals(msg)) {
      return ResponseEntity.status(500).body(ErrorResponse.of("PDF_RENDER_FAILED", "打印文件生成失败，请稍后重试。"));
    }
    return ResponseEntity.status(500).body(ErrorResponse.of("SERVER_ERROR", "服务器开小差了，请稍后再试。", msg));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> badJson(HttpMessageNotReadableException ex) {
    return ResponseEntity.status(422).body(ErrorResponse.of("BAD_JSON", "请求体不是合法 JSON。"));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex) {
    String debug = ex.getBindingResult().getFieldErrors().stream()
        .map(fe -> fe.getField() + ":" + fe.getDefaultMessage())
        .reduce((a, b) -> a + "; " + b)
        .orElse(null);
    return ResponseEntity.status(422).body(ErrorResponse.of("VALIDATION_ERROR", "参数校验失败。", debug));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> validation2(ConstraintViolationException ex) {
    return ResponseEntity.status(422).body(ErrorResponse.of("VALIDATION_ERROR", "参数校验失败。", ex.getMessage()));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> notFound(NoResourceFoundException ex) {
    return ResponseEntity.status(404).body(ErrorResponse.of("NOT_FOUND", "资源不存在或已被删除。"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> unknown(Exception ex) {
    return ResponseEntity.status(500).body(ErrorResponse.of("SERVER_ERROR", "服务器开小差了，请稍后再试。", ex.getClass().getName()));
  }
}
