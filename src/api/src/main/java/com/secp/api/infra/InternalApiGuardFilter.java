package com.secp.api.infra;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Prevent client/external users from accessing internal APIs.
 * Only applies to non-/client/** endpoints.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class InternalApiGuardFilter extends OncePerRequestFilter {

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.equals("/health")
        || path.startsWith("/auth/")
        || path.startsWith("/client/")
        || path.startsWith("/preview/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    AuthPrincipal p = AuthContext.get();
    if (p == null) {
      response.setStatus(401);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"UNAUTHORIZED\"}");
      return;
    }

    if (p.isAdmin() || "internal".equals(p.userType())) {
      filterChain.doFilter(request, response);
      return;
    }

    response.setStatus(403);
    response.setContentType("application/json");
    response.getWriter().write("{\"error\":\"FORBIDDEN\"}");
  }
}
