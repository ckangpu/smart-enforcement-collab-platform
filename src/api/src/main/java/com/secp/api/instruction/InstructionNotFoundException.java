package com.secp.api.instruction;

public class InstructionNotFoundException extends RuntimeException {
  public InstructionNotFoundException() {
    super("NOT_FOUND");
  }
}
