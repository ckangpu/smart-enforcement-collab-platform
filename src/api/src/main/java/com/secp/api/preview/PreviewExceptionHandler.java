package com.secp.api.preview;

import com.secp.api.infra.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PreviewExceptionHandler {

  @ExceptionHandler(PreviewNotFoundException.class)
  public ResponseEntity<ErrorResponse> notFound(PreviewNotFoundException ex) {
    return ResponseEntity.status(404).body(ErrorResponse.of("NOT_FOUND", "预览资源不存在或已过期。"));
  }

  @ExceptionHandler(PreviewTooLargeException.class)
  public ResponseEntity<ErrorResponse> tooLarge(PreviewTooLargeException ex) {
    return ResponseEntity.status(413).body(ErrorResponse.of("TOO_LARGE", "文件过大，无法预览。"));
  }

  @ExceptionHandler(PreviewRateLimitedException.class)
  public ResponseEntity<ErrorResponse> rateLimited(PreviewRateLimitedException ex) {
    return ResponseEntity.status(429).body(ErrorResponse.of("RATE_LIMITED", "请求过于频繁，请稍后再试。"));
  }
}
