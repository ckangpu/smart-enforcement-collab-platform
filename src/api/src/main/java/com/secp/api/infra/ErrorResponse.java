package com.secp.api.infra;

public record ErrorResponse(String code, String message, String debug) {

  /**
   * Backward-compatible alias for legacy clients that expect {"error": "..."}.
   * Keep both "code" and "error" in JSON.
   */
  public String error() {
    return code;
  }

  public static ErrorResponse of(String code, String message) {
    return new ErrorResponse(code, message, null);
  }

  public static ErrorResponse of(String code, String message, String debug) {
    return new ErrorResponse(code, message, debug);
  }
}
