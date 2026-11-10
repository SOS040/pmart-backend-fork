package com.pmart.gateway.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

/**
 * 주체 차단 목록 저장(ADR-0054 ⑤) — 게이트웨이 Redis. 키 {@code (iss, sub)} → 값 "이 시각(epoch 초) 이하에 발급된 토큰은 거부".
 * 정본이 아니다: 발급자·업무 서비스의 {@code SubjectBlocked} 사건을 받아 채우고, 항목은 가장 긴 access token 수명이 지나면 사라진다(그 뒤엔
 * 발급자가 새 토큰을 주지 않는다). 사람이 직접 쓰지 않는다.
 */
public class SubjectBlocklist {

  static final String KEY_PREFIX = "pmart:gw:subject-block:";

  /** 더 늦은 차단 시각일 때만 덮는다(재전달·재처리·순서가 섞여도 결과가 같다). 1 = 썼음, 0 = 이미 같거나 늦은 값. */
  private static final RedisScript<Long> SET_IF_LATER =
      RedisScript.of(
          """
          local c = redis.call('GET', KEYS[1])
          if c and tonumber(c) >= tonumber(ARGV[1]) then return 0 end
          redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
          return 1
          """,
          Long.class);

  private final ReactiveStringRedisTemplate redis;

  public SubjectBlocklist(ReactiveStringRedisTemplate redis) {
    this.redis = redis;
  }

  static String key(String issuer, String subject) {
    return KEY_PREFIX + issuer + "|" + subject;
  }

  /** 차단 시각(epoch 초). 항목이 없으면 빈 Mono. */
  public Mono<Long> blockedBefore(String issuer, String subject) {
    return redis.opsForValue().get(key(issuer, subject)).map(Long::parseLong);
  }

  /**
   * @param ttl 남길 기간(0 이하면 쓰지 않는다 — 이미 그 전 토큰은 모두 만료)
   * @return 썼으면 true
   */
  public Mono<Boolean> record(String issuer, String subject, Instant blockedBefore, Duration ttl) {
    if (ttl.isZero() || ttl.isNegative()) {
      return Mono.just(false);
    }
    return redis
        .execute(
            SET_IF_LATER,
            List.of(key(issuer, subject)),
            List.of(String.valueOf(blockedBefore.getEpochSecond()), String.valueOf(ttl.toMillis())))
        .next()
        .map(r -> r == 1L);
  }
}
