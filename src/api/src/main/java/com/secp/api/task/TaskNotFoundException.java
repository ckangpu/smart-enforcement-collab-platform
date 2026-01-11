package com.secp.api.task;

public class TaskNotFoundException extends RuntimeException {
  public TaskNotFoundException() {
    super("NOT_FOUND");
  }
}
