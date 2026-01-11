package com.secp.api.admin;

public class AdminNotFoundException extends RuntimeException {
  public AdminNotFoundException() {
    super("NOT_FOUND");
  }
}
