package com.secp.api.payment;

import com.secp.api.auth.AuthContext;
import com.secp.api.auth.AuthPrincipal;
import com.secp.api.payment.dto.CreatePaymentRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/payments")
public class PaymentController {

  private final PaymentService paymentService;

  @PostMapping
  public ResponseEntity<String> create(@Valid @RequestBody CreatePaymentRequest req,
                                       HttpServletRequest httpReq,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
    AuthPrincipal principal = AuthContext.getRequired();
    PaymentService.JsonResponse resp = paymentService.createPayment(principal, req, httpReq, idemKey);
    return ResponseEntity.status(resp.statusCode()).body(resp.bodyJson());
  }

  @PostMapping("/{paymentId}/correct")
  public ResponseEntity<String> correct(@PathVariable UUID paymentId,
                                        @RequestParam String reason,
                                        HttpServletRequest httpReq,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
    AuthPrincipal principal = AuthContext.getRequired();
    PaymentService.JsonResponse resp = paymentService.correctPayment(principal, paymentId, reason, httpReq, idemKey);
    return ResponseEntity.status(resp.statusCode()).body(resp.bodyJson());
  }
}
