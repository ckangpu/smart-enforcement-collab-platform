package com.secp.api.me;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/me/tasks")
public class MeTaskController {

  private final MeTaskService meTaskService;

  @GetMapping
  public ResponseEntity<List<MeTaskDto>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) UUID projectId,
      @RequestParam(required = false) UUID caseId
  ) {
    AuthPrincipal principal = AuthContext.getRequired();
    List<MeTaskDto> rows = meTaskService.listMyTasks(principal, status, overdueOnly, projectId, caseId);
    return ResponseEntity.ok(rows);
  }
}
