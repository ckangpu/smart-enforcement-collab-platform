package com.secp.api.infra;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class BizCodeService {

  private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

  private final JdbcTemplate jdbc;

  public BizCodeService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public String nextCode(String prefix, LocalDate acceptedAtOrNull) {
    if (prefix == null || prefix.isBlank()) {
      throw new IllegalArgumentException("prefix不能为空");
    }

    LocalDate baseDate = acceptedAtOrNull != null ? acceptedAtOrNull : LocalDate.now();
    String yyyymm = baseDate.format(YYYYMM);

    int nextSeq = nextSeqWithRetry(prefix, yyyymm, 3);
    return prefix + yyyymm + String.format("%04d", nextSeq);
  }

  private int nextSeqWithRetry(String prefix, String yyyymm, int retries) {
    for (int i = 0; i < retries; i++) {
      Integer last = jdbc.query(
          "select last_seq from biz_code_seq where prefix=? and yyyymm=? for update",
          ps -> {
            ps.setString(1, prefix);
            ps.setString(2, yyyymm);
          },
          rs -> rs.next() ? rs.getInt(1) : null
      );

      if (last != null) {
        int next = last + 1;
        jdbc.update(
            "update biz_code_seq set last_seq=? where prefix=? and yyyymm=?",
            next,
            prefix,
            yyyymm
        );
        return next;
      }

      try {
        jdbc.update(
            "insert into biz_code_seq(prefix, yyyymm, last_seq) values (?,?,1)",
            prefix,
            yyyymm
        );
        return 1;
      } catch (DuplicateKeyException e) {
        // concurrent insert, retry
      }
    }

    throw new IllegalStateException("生成编号失败，请重试");
  }
}
