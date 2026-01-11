package com.secp.api.client;

public class ClientNotFoundException extends RuntimeException {

  public ClientNotFoundException() {
    super("NOT_FOUND");
  }
}
