package com.secp.api.client;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.client.dto.CreatePaymentDisputeRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/client/payments")
public class ClientPaymentDisputeController {

  private final ClientPaymentDisputeService clientPaymentDisputeService;

  @PostMapping("/{paymentId}/disputes")
  public ResponseEntity<String> create(@PathVariable UUID paymentId,
                                       @Valid @RequestBody CreatePaymentDisputeRequest req,
                                       HttpServletRequest httpReq,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
    AuthPrincipal client = ClientAuthContext.requireClient();
    ClientPaymentDisputeService.JsonResponse resp = clientPaymentDisputeService.createPaymentDispute(client, paymentId, req, httpReq, idemKey);
    return ResponseEntity.status(resp.statusCode()).body(resp.bodyJson());
  }
}
