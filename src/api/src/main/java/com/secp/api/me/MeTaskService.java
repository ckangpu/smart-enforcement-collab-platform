package com.secp.api.me;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.infra.tx.TransactionalExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MeTaskService {

  private final TransactionalExecutor tx;
  private final MeTaskRepository meTaskRepository;

  public List<MeTaskDto> listMyTasks(AuthPrincipal principal,
                                    String status,
                                    Boolean overdueOnly,
                                    UUID projectId,
                                    UUID caseId) {
    return tx.execute(principal, () -> {
      String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase();
      return meTaskRepository.list(principal.userId(), normalizedStatus, overdueOnly, projectId, caseId);
    });
  }
}
