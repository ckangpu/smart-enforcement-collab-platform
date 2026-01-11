package com.secp.api.evidence;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.evidence.dto.CreateEvidenceRequest;
import com.secp.api.evidence.dto.CreateEvidenceResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/evidences")
public class EvidenceController {

  private final EvidenceService evidenceService;

  @PostMapping
  public ResponseEntity<CreateEvidenceResponse> create(@Valid @RequestBody CreateEvidenceRequest req,
                                                      HttpServletRequest httpReq) {
    AuthPrincipal principal = AuthContext.getRequired();
    CreateEvidenceResponse resp = evidenceService.create(principal, req, httpReq);
    return ResponseEntity.status(201).body(resp);
  }
}
