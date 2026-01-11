package com.secp.api.infra.tx;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.auth.UserGroupService;
import com.secp.api.infra.RlsSessionJdbc;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class TransactionalExecutor {

  private final TransactionTemplate tx;
  private final RlsSessionJdbc rlsSessionJdbc;
  private final UserGroupService userGroupService;

  public TransactionalExecutor(
      PlatformTransactionManager txManager,
      RlsSessionJdbc rlsSessionJdbc,
      UserGroupService userGroupService
  ) {
    this.tx = new TransactionTemplate(txManager);
    this.rlsSessionJdbc = rlsSessionJdbc;
    this.userGroupService = userGroupService;
  }

  public <T> T execute(AuthPrincipal principal, Supplier<T> supplier) {
    return tx.execute(status -> {
      List<UUID> groupIds = userGroupService.getGroupIds(principal.userId());
      String csv = String.join(",", groupIds.stream().map(UUID::toString).toList());
      rlsSessionJdbc.applyRlsSession(principal.userId().toString(), principal.isAdmin(), csv);
      return supplier.get();
    });
  }

  public void run(AuthPrincipal principal, Runnable runnable) {
    execute(principal, () -> {
      runnable.run();
      return null;
    });
  }
}
