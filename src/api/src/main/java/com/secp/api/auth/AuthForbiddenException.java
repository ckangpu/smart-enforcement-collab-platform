package com.secp.api.auth;

public class AuthForbiddenException extends RuntimeException {
  public AuthForbiddenException() {
    super("FORBIDDEN");
  }
}
