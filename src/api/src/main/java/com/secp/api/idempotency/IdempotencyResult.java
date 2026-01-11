package com.secp.api.idempotency;

public record IdempotencyResult(boolean replay, int statusCode, String responseBodyJson) {
  public static IdempotencyResult replay(int statusCode, String responseBodyJson) {
    return new IdempotencyResult(true, statusCode, responseBodyJson);
  }

  public static IdempotencyResult proceed() {
    return new IdempotencyResult(false, 0, null);
  }
}
