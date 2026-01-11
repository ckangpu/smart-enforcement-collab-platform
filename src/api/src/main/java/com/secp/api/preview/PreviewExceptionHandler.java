package com.secp.api.preview;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PreviewExceptionHandler {

  @ExceptionHandler(PreviewNotFoundException.class)
  public ResponseEntity<String> notFound(PreviewNotFoundException ex) {
    return ResponseEntity.status(404).body("{\"error\":\"NOT_FOUND\"}");
  }

  @ExceptionHandler(PreviewTooLargeException.class)
  public ResponseEntity<String> tooLarge(PreviewTooLargeException ex) {
    return ResponseEntity.status(413).body("{\"error\":\"TOO_LARGE\"}");
  }

  @ExceptionHandler(PreviewRateLimitedException.class)
  public ResponseEntity<String> rateLimited(PreviewRateLimitedException ex) {
    return ResponseEntity.status(429).body("{\"error\":\"RATE_LIMITED\"}");
  }
}
