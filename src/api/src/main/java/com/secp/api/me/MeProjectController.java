package com.secp.api.me;

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
@RequestMapping("/me/projects")
public class MeProjectController {

  private final MeProjectService meProjectService;

  @GetMapping
  public ResponseEntity<List<MeProjectDto>> list() {
    AuthPrincipal principal = AuthContext.getRequired();
    return ResponseEntity.ok(meProjectService.listMyProjects(principal));
  }
}
