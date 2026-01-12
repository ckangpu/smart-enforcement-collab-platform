package com.secp.api.workbench;

public class WorkbenchValidationException extends RuntimeException {

  private final int httpStatus;

  public WorkbenchValidationException(String message, int httpStatus) {
    super(message);
    this.httpStatus = httpStatus;
  }

  public int httpStatus() {
    return httpStatus;
  }
}
