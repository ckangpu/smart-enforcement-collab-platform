package com.secp.api.project;

public class ProjectNotFoundException extends RuntimeException {
  public ProjectNotFoundException() {
    super("NOT_FOUND");
  }
}
