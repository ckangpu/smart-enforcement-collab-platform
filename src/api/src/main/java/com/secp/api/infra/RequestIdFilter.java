package com.secp.api.infra;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestIdFilter implements Filter {

  public static final String REQ_ID_ATTR = "requestId";
  public static final String REQ_ID_HEADER = "X-Request-Id";

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse resp = (HttpServletResponse) response;

    String rid = req.getHeader(REQ_ID_HEADER);
    if (rid == null || rid.isBlank()) {
      rid = UUID.randomUUID().toString();
    }
    req.setAttribute(REQ_ID_ATTR, rid);
    resp.setHeader(REQ_ID_HEADER, rid);

    chain.doFilter(request, response);
  }
}
