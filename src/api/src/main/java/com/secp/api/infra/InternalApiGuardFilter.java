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
    if (path == null) return false;
    if (path.equals("/health")
        || path.startsWith("/auth/")
        || path.startsWith("/client/")
        || path.startsWith("/preview/")) {
      return true;
    }

    // Static UI and common static assets must be publicly accessible.
    if (path.equals("/ui") || path.startsWith("/ui/")) return true;
    if (path.equals("/favicon.ico")) return true;

    String lower = path.toLowerCase();
    return lower.endsWith(".css")
        || lower.endsWith(".js")
        || lower.endsWith(".png")
        || lower.endsWith(".svg")
        || lower.endsWith(".map")
        || lower.endsWith(".ico")
        || lower.endsWith(".jpg")
        || lower.endsWith(".jpeg")
        || lower.endsWith(".gif")
        || lower.endsWith(".webp")
        || lower.endsWith(".woff")
        || lower.endsWith(".woff2")
        || lower.endsWith(".ttf")
        || lower.endsWith(".eot");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    AuthPrincipal p = AuthContext.get();
    if (p == null) {
      response.setStatus(401);
      response.setContentType("application/json");
      response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"error\":\"UNAUTHORIZED\",\"message\":\"未登录或登录已失效，请重新登录。\"}");
      return;
    }

    if (p.isAdmin() || "internal".equals(p.userType())) {
      filterChain.doFilter(request, response);
      return;
    }

    response.setStatus(403);
    response.setContentType("application/json");
    response.getWriter().write("{\"code\":\"FORBIDDEN\",\"error\":\"FORBIDDEN\",\"message\":\"仅限内部账号访问。\"}");
  }
}
