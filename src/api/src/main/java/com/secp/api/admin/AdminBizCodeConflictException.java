package com.secp.api.admin;

public class AdminBizCodeConflictException extends RuntimeException {
  public AdminBizCodeConflictException() {
    super("BIZ_CODE_CONFLICT");
  }
}
