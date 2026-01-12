package com.secp.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthFilter extends OncePerRequestFilter {

  private final JwtService jwtService;

  public JwtAuthFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    if (path == null) return false;
    if (path.equals("/health") || path.startsWith("/auth/")) return true;

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

    String auth = request.getHeader("Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) {
      response.setStatus(401);
      response.setContentType("application/json");
      response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"error\":\"UNAUTHORIZED\",\"message\":\"未登录或登录已失效，请重新登录。\"}");
      return;
    }

    String token = auth.substring("Bearer ".length());
    AuthPrincipal principal;
    try {
      principal = jwtService.parsePrincipal(jwtService.verify(token));
    } catch (Exception ex) {
      response.setStatus(401);
      response.setContentType("application/json");
      response.getWriter().write("{\"code\":\"INVALID_TOKEN\",\"error\":\"INVALID_TOKEN\",\"message\":\"登录已失效，请重新登录。\"}");
      return;
    }

    AuthContext.set(principal);
    try {
      filterChain.doFilter(request, response);
    } finally {
      AuthContext.clear();
    }
  }
}
