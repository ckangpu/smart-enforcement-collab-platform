package com.secp.api.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class SmsCodeService {

  private final StringRedisTemplate redis;
  private final SecureRandom random = new SecureRandom();

  @Value("${secp.sms.code-ttl-seconds:300}")
  private long codeTtlSeconds;

  @Value("${secp.sms.send-cooldown-seconds:60}")
  private long cooldownSeconds;

  @Value("${secp.sms.daily-max-per-phone:10}")
  private long dailyMax;

  public void sendCode(String phone) {
    String cooldownKey = "sms:cooldown:" + phone;
    if (Boolean.TRUE.equals(redis.hasKey(cooldownKey))) {
      throw new IllegalStateException("SMS_COOLDOWN");
    }

    String dailyKey = "sms:daily:" + phone + ":" + java.time.LocalDate.now();
    Long cnt = redis.opsForValue().increment(dailyKey);
    if (cnt != null && cnt == 1) {
      redis.expire(dailyKey, Duration.ofHours(26));
    }
    if (cnt != null && cnt > dailyMax) {
      throw new IllegalStateException("SMS_DAILY_LIMIT");
    }

    String code = String.format("%06d", random.nextInt(1_000_000));
    redis.opsForValue().set("sms:code:" + phone, code, Duration.ofSeconds(codeTtlSeconds));
    redis.opsForValue().set(cooldownKey, "1", Duration.ofSeconds(cooldownSeconds));

    // V1 demo: log the code instead of sending real SMS.
    System.out.println("[sms] phone=" + phone + " code=" + code);
  }

  public boolean verify(String phone, String code) {
    String key = "sms:code:" + phone;
    String expected = redis.opsForValue().get(key);
    if (expected == null) {
      return false;
    }
    boolean ok = expected.equals(code);
    if (ok) {
      redis.delete(key);
    }
    return ok;
  }
}
