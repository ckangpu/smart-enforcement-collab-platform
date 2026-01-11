package com.secp.api.me;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.infra.tx.TransactionalExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MeProjectService {

  private final TransactionalExecutor tx;
  private final MeProjectRepository meProjectRepository;

  public List<MeProjectDto> listMyProjects(AuthPrincipal principal) {
    return tx.execute(principal, () -> meProjectRepository.list());
  }
}
