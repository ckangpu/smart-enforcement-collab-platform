package com.secp.api.admin;

public class AdminForbiddenException extends RuntimeException {
  public AdminForbiddenException() {
    super("FORBIDDEN");
  }
}
