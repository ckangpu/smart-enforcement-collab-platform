package com.secp.api.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserGroupService {

  private final StringRedisTemplate redis;
  private final JdbcTemplate jdbc;

  @Value("${secp.auth.group-cache-ttl-seconds:45}")
  private long groupCacheTtlSeconds;

  public List<UUID> getGroupIds(UUID userId) {
    String key = cacheKey(userId);
    String cached = redis.opsForValue().get(key);
    if (cached != null) {
      if (cached.isBlank()) {
        return List.of();
      }
      return Arrays.stream(cached.split(","))
          .filter(s -> !s.isBlank())
          .map(UUID::fromString)
          .toList();
    }

    List<UUID> groupIds = jdbc.query(
        "select group_id from user_group where user_id=?",
        (rs, i) -> UUID.fromString(rs.getString(1)),
        userId
    );

    String csv = groupIds.stream().map(UUID::toString).collect(Collectors.joining(","));
    redis.opsForValue().set(key, csv, Duration.ofSeconds(groupCacheTtlSeconds));
    return groupIds;
  }

  public void evict(UUID userId) {
    redis.delete(cacheKey(userId));
  }

  private String cacheKey(UUID userId) {
    return "user:groups:" + userId;
  }
}
