package com.secp.api.auth;

public final class AuthContext {
  private static final ThreadLocal<AuthPrincipal> CURRENT = new ThreadLocal<>();

  private AuthContext() {
  }

  public static void set(AuthPrincipal principal) {
    CURRENT.set(principal);
  }

  public static AuthPrincipal get() {
    return CURRENT.get();
  }

  public static AuthPrincipal getRequired() {
    AuthPrincipal p = CURRENT.get();
    if (p == null) {
      throw new UnauthorizedException();
    }
    return p;
  }

  public static void clear() {
    CURRENT.remove();
  }
}
