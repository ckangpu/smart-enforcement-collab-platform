package com.secp.api.client;

public class ClientForbiddenException extends RuntimeException {
  public ClientForbiddenException(String message) {
    super(message);
  }
}
