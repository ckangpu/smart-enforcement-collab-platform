package com.secp.api.idempotency;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

  private final StringRedisTemplate redis;
  private final JdbcTemplate jdbc;

  @Value("${secp.idempotency.ttl-seconds:86400}")
  private long ttlSeconds;

  @Value("${secp.idempotency.in-progress-ttl-seconds:120}")
  private long inProgressTtlSeconds;

  private static final Object TX_LOCK_TOKENS_RESOURCE = new Object();

  private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
      "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
      Long.class
  );

  /**
   * Must be called inside the same business transaction after RLS session is applied.
   *
   * Scope example: "POST /payments" or "POST /payments/{id}/correct".
   */
  public IdempotencyResult preCheck(UUID userId, String scope, String idemKey, String requestHash) {
    if (idemKey == null || idemKey.isBlank()) {
      return IdempotencyResult.proceed();
    }
    requireActiveTransaction();

    String doneKey = doneKey(userId, scope, idemKey);
    String lockKey = lockKey(userId, scope, idemKey);

    // Fast path: completed response cached
    String cached = redis.opsForValue().get(doneKey);
    if (cached != null) {
      return parseCached(cached);
    }

    try {
      jdbc.update("""
          insert into idempotency_record(user_id, scope, idem_key, request_hash, expires_at)
          values (?,?,?,?, now() + (? * interval '1 second'))
          """, userId, scope, idemKey, requestHash, ttlSeconds);

      if (!acquireLockForTx(lockKey)) {
        return IdempotencyResult.replay(409, "{\"error\":\"IDEMPOTENCY_IN_PROGRESS\"}");
      }
      return IdempotencyResult.proceed();
    } catch (DuplicateKeyException dup) {
      Map<String, Object> row = jdbc.queryForMap(
          "select completed, status_code, response_body, request_hash, created_at from idempotency_record where user_id=? and scope=? and idem_key=?",
          userId, scope, idemKey);

      String existingHash = (String) row.get("request_hash");
      if (existingHash != null && requestHash != null && !existingHash.equals(requestHash)) {
        return IdempotencyResult.replay(409, "{\"error\":\"IDEMPOTENCY_KEY_REUSED_DIFFERENT_REQUEST\"}");
      }

      Boolean completed = (Boolean) row.get("completed");
      Integer status = (Integer) row.get("status_code");
      Object body = row.get("response_body");
      if (Boolean.TRUE.equals(completed) && status != null && body != null) {
        String json = String.valueOf(body);
        cacheAfterCommit(doneKey, status, json);
        return IdempotencyResult.replay(status, json);
      }

      // processing: close concurrency window using (DB state + redis lock + staleness)
      if (Boolean.TRUE.equals(redis.hasKey(lockKey))) {
        return IdempotencyResult.replay(409, "{\"error\":\"IDEMPOTENCY_IN_PROGRESS\"}");
      }

      Instant createdAt = toInstant(row.get("created_at"));
      Instant now = Instant.now();
      if (createdAt == null) {
        // Conservative fallback
        return IdempotencyResult.replay(409, "{\"error\":\"IDEMPOTENCY_IN_PROGRESS\"}");
      }

      long takeoverThresholdSeconds = Math.max(1, inProgressTtlSeconds * 2);
      boolean stale = createdAt.isBefore(now.minusSeconds(takeoverThresholdSeconds));
      if (!stale) {
        return IdempotencyResult.replay(409, "{\"error\":\"IDEMPOTENCY_IN_PROGRESS\"}");
      }

      if (acquireLockForTx(lockKey)) {
        return IdempotencyResult.proceed();
      }
      return IdempotencyResult.replay(409, "{\"error\":\"IDEMPOTENCY_IN_PROGRESS\"}");
    }
  }

  public void complete(UUID userId, String scope, String idemKey, int statusCode, String responseJson) {
    if (idemKey == null || idemKey.isBlank()) {
      return;
    }
    requireActiveTransaction();

    jdbc.update("""
        update idempotency_record
        set completed=true, status_code=?, response_body=?::jsonb, completed_at=now()
        where user_id=? and scope=? and idem_key=?
        """, statusCode, responseJson, userId, scope, idemKey);

    String doneKey = doneKey(userId, scope, idemKey);
    cacheAfterCommit(doneKey, statusCode, responseJson);
  }

  private void cacheAfterCommit(String key, int statusCode, String json) {
    scheduleAfterCommit(() ->
        redis.opsForValue().set(key, statusCode + "\n" + json, Duration.ofSeconds(ttlSeconds))
    );
  }

  private IdempotencyResult parseCached(String cached) {
    int idx = cached.indexOf('\n');
    if (idx > 0) {
      int status = Integer.parseInt(cached.substring(0, idx));
      String body = cached.substring(idx + 1);
      return IdempotencyResult.replay(status, body);
    }
    return IdempotencyResult.replay(200, cached);
  }

  private String doneKey(UUID userId, String scope, String idemKey) {
    return "idem:done:" + userId + ":" + scope + ":" + idemKey;
  }

  private String lockKey(UUID userId, String scope, String idemKey) {
    return "idem:lock:" + userId + ":" + scope + ":" + idemKey;
  }

  private void requireActiveTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Idempotency must be used inside business transaction");
    }
  }

  private boolean acquireLockForTx(String lockKey) {
    String token = UUID.randomUUID().toString();
    boolean acquired = Boolean.TRUE.equals(
        redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofSeconds(inProgressTtlSeconds))
    );
    if (!acquired) {
      return false;
    }

    Map<String, String> tokens = getOrCreateTxLockTokens();
    if (!tokens.containsKey(lockKey)) {
      tokens.put(lockKey, token);
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCompletion(int status) {
          safeReleaseLock(lockKey, token);
        }
      });
    }
    return true;
  }

  private void safeReleaseLock(String lockKey, String token) {
    try {
      redis.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(lockKey), token);
    } catch (Exception ignored) {
      // Best-effort; TTL is a secondary safety net.
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> getOrCreateTxLockTokens() {
    Object existing = TransactionSynchronizationManager.getResource(TX_LOCK_TOKENS_RESOURCE);
    if (existing instanceof Map<?, ?> map) {
      return (Map<String, String>) map;
    }
    Map<String, String> created = new HashMap<>();
    TransactionSynchronizationManager.bindResource(TX_LOCK_TOKENS_RESOURCE, created);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCompletion(int status) {
        TransactionSynchronizationManager.unbindResourceIfPossible(TX_LOCK_TOKENS_RESOURCE);
      }
    });
    return created;
  }

  private void scheduleAfterCommit(Runnable action) {
    Objects.requireNonNull(action, "action");
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        action.run();
      }
    });
  }

  private Instant toInstant(Object value) {
    if (value instanceof Timestamp ts) {
      return ts.toInstant();
    }
    return null;
  }
}
