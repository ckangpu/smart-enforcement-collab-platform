package com.secp.api.preview;

public class PreviewTooLargeException extends RuntimeException {
  public PreviewTooLargeException(String reason) {
    super(reason);
  }
}
