package com.secp.api.client;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;

public final class ClientAuthContext {

  private ClientAuthContext() {
  }

  public static AuthPrincipal requireClient() {
    AuthPrincipal p = AuthContext.getRequired();
    if (!"client".equals(p.userType())) {
      throw new ClientForbiddenException("CLIENT_ONLY");
    }
    return p;
  }
}
