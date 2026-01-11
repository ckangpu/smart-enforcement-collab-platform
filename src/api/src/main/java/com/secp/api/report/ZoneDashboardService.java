package com.secp.api.report;

import com.secp.api.auth.AuthPrincipal;
import com.secp.api.infra.tx.TransactionalExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ZoneDashboardService {

  private final TransactionalExecutor tx;
  private final ZoneDashboardRepository zoneDashboardRepository;
  private final Clock clock;

  public List<ZoneDashboardRowDto> zoneDashboard(AuthPrincipal principal) {
    Instant now = clock.instant();
    OffsetDateTime nowAt = OffsetDateTime.ofInstant(now, ZoneId.of("UTC"));
    String dayKey = DateTimeFormatter.BASIC_ISO_DATE.format(now.atZone(ZoneId.of("Asia/Shanghai")));
    return tx.execute(principal, () -> zoneDashboardRepository.zoneDashboard(nowAt, dayKey));
  }
}
