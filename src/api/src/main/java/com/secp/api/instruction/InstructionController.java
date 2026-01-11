package com.secp.api.instruction;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.instruction.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping
public class InstructionController {

  private final InstructionService instructionService;
  private final InstructionQueryService instructionQueryService;

  @PostMapping("/instructions")
  public ResponseEntity<CreateInstructionResponse> create(@Valid @RequestBody CreateInstructionRequest req,
                                                          HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    CreateInstructionResponse resp = instructionService.create(principal, req, httpReq);
    return ResponseEntity.status(201).body(resp);
  }

  @PostMapping("/instructions/{instructionId}/issue")
  public ResponseEntity<String> issue(@PathVariable UUID instructionId,
                                      @RequestBody(required = false) IssueInstructionRequest req,
                                      HttpServletRequest httpReq,
                                      @RequestHeader(value = "Idempotency-Key", required = true) String idemKey) {
    AuthPrincipal principal = AuthContext.getRequired();
    InstructionService.JsonResponse resp = instructionService.issue(principal, instructionId, req, httpReq, idemKey);
    return ResponseEntity.status(resp.statusCode()).body(resp.bodyJson());
  }

  @PostMapping("/instruction-items/{instructionItemId}/status")
  public ResponseEntity<UpdateInstructionItemStatusResponse> updateStatus(@PathVariable UUID instructionItemId,
                                                                          @Valid @RequestBody UpdateInstructionItemStatusRequest req,
                                                                          HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    UpdateInstructionItemStatusResponse resp = instructionService.updateItemStatus(principal, instructionItemId, req, httpReq);
    return ResponseEntity.ok(resp);
  }

  @GetMapping("/instructions/{instructionId}")
  public ResponseEntity<InstructionDetailResponse> getInstruction(@PathVariable UUID instructionId) {
    AuthPrincipal principal = AuthContext.getRequired();
    InstructionDetailResponse resp = instructionQueryService.getInstructionDetail(principal, instructionId);
    return ResponseEntity.ok(resp);
  }
}
