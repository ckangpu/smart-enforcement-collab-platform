package com.secp.api.instruction;

public class InstructionConflictException extends RuntimeException {
  public InstructionConflictException(String reason) {
    super(reason);
  }
}
