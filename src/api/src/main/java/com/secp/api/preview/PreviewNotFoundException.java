package com.secp.api.preview;

public class PreviewNotFoundException extends RuntimeException {
  public PreviewNotFoundException() {
    super("NOT_FOUND");
  }
}
