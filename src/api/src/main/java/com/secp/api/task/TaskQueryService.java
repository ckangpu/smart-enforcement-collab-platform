package com.secp.api.task;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.infra.tx.TransactionalExecutor;
import com.secp.api.task.dto.TaskDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskQueryService {

  private final TransactionalExecutor tx;
  private final TaskQueryRepository taskQueryRepository;

  public TaskDetailResponse getTaskDetail(AuthPrincipal principal, UUID taskId) {
    return tx.execute(principal, () -> taskQueryRepository.getTaskDetail(taskId)
        .orElseThrow(TaskNotFoundException::new));
  }
}
