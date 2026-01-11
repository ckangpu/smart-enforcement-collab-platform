package com.secp.api.instruction;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.infra.tx.TransactionalExecutor;
import com.secp.api.instruction.dto.InstructionDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InstructionQueryService {

  private final TransactionalExecutor tx;
  private final InstructionRepository instructionRepository;

  public InstructionDetailResponse getInstructionDetail(AuthPrincipal principal, java.util.UUID instructionId) {
    return tx.execute(principal, () -> instructionRepository.getInstructionDetail(instructionId)
        .orElseThrow(InstructionNotFoundException::new));
  }
}
