package com.secp.api.infra;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import com.secp.api.auth.AuthContext;

import java.io.IOException;

@Component
public class DemoAuthContextFilter implements Filter {

  // Demo headers:
  // X-Demo-User-Id: <uuid>
  // X-Demo-Is-Admin: true/false
  // X-Demo-Group-Ids: uuid1,uuid2
  public static final String USER_ID_ATTR = "userId";
  public static final String IS_ADMIN_ATTR = "isAdmin";
  public static final String GROUP_IDS_ATTR = "groupIds";

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {

    HttpServletRequest req = (HttpServletRequest) request;

    // If JWT auth already set, keep it.
    if (AuthContext.get() != null) {
      chain.doFilter(request, response);
      return;
    }

    String userId = req.getHeader("X-Demo-User-Id");
    String isAdmin = req.getHeader("X-Demo-Is-Admin");
    String groupIds = req.getHeader("X-Demo-Group-Ids");

    if (userId == null) userId = "";
    if (isAdmin == null) isAdmin = "false";
    if (groupIds == null) groupIds = "";

    req.setAttribute(USER_ID_ATTR, userId);
    req.setAttribute(IS_ADMIN_ATTR, isAdmin);
    req.setAttribute(GROUP_IDS_ATTR, groupIds);

    chain.doFilter(request, response);
  }
}
