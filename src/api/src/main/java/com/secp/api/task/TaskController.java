package com.secp.api.task;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.task.dto.TaskDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/tasks")
public class TaskController {

  private final TaskQueryService taskQueryService;

  @GetMapping("/{taskId}")
  public ResponseEntity<TaskDetailResponse> getTask(@PathVariable UUID taskId) {
    AuthPrincipal principal = AuthContext.getRequired();
    TaskDetailResponse resp = taskQueryService.getTaskDetail(principal, taskId);
    return ResponseEntity.ok(resp);
  }
}
