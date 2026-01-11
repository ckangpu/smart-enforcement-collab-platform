package com.secp.api.report;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/reports")
public class ZoneDashboardController {

  private final ZoneDashboardService zoneDashboardService;

  @GetMapping("/zone-dashboard")
  public ResponseEntity<List<ZoneDashboardRowDto>> zoneDashboard() {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(zoneDashboardService.zoneDashboard(principal));
  }
}
