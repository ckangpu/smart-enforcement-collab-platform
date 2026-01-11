package com.secp.api.instruction;

public class UnprocessableEntityException extends RuntimeException {
  public UnprocessableEntityException(String reason) {
    super(reason);
  }
}
