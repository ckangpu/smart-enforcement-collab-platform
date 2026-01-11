package com.secp.api.payment;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.infra.tx.TransactionalExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentQueryService {

  private final TransactionalExecutor tx;
  private final PaymentRepository paymentRepository;

  public List<PaymentRepository.PaymentRow> listEffectivePaymentsByCase(AuthPrincipal principal, UUID caseId) {
    return tx.execute(principal, () -> paymentRepository.listEffectiveByCase(caseId));
  }
}
