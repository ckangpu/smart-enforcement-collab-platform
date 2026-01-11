package com.secp.api.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class JwtService {

  private final Algorithm algorithm;

  @Value("${secp.jwt.issuer:secp-api}")
  private String issuer;

  @Value("${secp.jwt.ttl-seconds:86400}")
  private long ttlSeconds;

  public JwtService(@Value("${secp.jwt.secret:dev-secret-change-me}") String secret) {
    this.algorithm = Algorithm.HMAC256(secret);
  }

    public String sign(AuthPrincipal principal) {
    Instant now = Instant.now();
    return JWT.create()
        .withIssuer(issuer)
        .withIssuedAt(now)
        .withExpiresAt(now.plusSeconds(ttlSeconds))
      .withSubject(principal.userId().toString())
      .withClaim("is_admin", principal.isAdmin())
      .withClaim("username", principal.username())
        .withClaim("user_type", principal.userType())
        .sign(algorithm);
  }

  public DecodedJWT verify(String token) {
    return JWT.require(algorithm).withIssuer(issuer).build().verify(token);
  }

  public AuthPrincipal parsePrincipal(DecodedJWT jwt) {
    UUID userId = UUID.fromString(jwt.getSubject());
    boolean isAdmin = Boolean.TRUE.equals(jwt.getClaim("is_admin").asBoolean());
    String username = jwt.getClaim("username").asString();
    String userType = jwt.getClaim("user_type").asString();
    return new AuthPrincipal(userId, isAdmin, username, userType);
  }
}
