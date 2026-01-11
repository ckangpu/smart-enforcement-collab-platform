package com.secp.api.preview;

public class PreviewRateLimitedException extends RuntimeException {
  public PreviewRateLimitedException() {
    super("RATE_LIMITED");
  }
}
