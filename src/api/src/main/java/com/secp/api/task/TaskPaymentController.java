package com.secp.api.task;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.task.dto.CreateTaskPaymentRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/tasks")
public class TaskPaymentController {

  private final TaskPaymentService taskPaymentService;

  @PostMapping("/{taskId}/payments")
  public ResponseEntity<String> createPayment(@PathVariable UUID taskId,
                                              @Valid @RequestBody CreateTaskPaymentRequest req,
                                              HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    TaskPaymentService.JsonResponse resp = taskPaymentService.createPaymentForTask(principal, taskId, req, httpReq);
    return ResponseEntity.status(resp.statusCode()).body(resp.bodyJson());
  }
}
