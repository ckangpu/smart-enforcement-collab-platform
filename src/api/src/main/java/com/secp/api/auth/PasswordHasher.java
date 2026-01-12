package com.secp.api.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public String hash(String rawPassword) {
    return encoder.encode(rawPassword);
  }

  public boolean matches(String rawPassword, String passwordHash) {
    if (rawPassword == null || passwordHash == null) return false;
    return encoder.matches(rawPassword, passwordHash);
  }
}
