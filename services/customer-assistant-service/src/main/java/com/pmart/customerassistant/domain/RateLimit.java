package com.pmart.customerassistant.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 고객별 요청 제한(사용자 결정 2026-11-09 C3): 1분에 {@code perMinute} 개, 하루(Asia/Seoul 자정부터) {@code perDay} 개. 새
 * 질문(새 Idempotency-Key)만 센다.
 */
public record RateLimit(int perMinute, int perDay) {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  /** 판정 결과. 허용이면 retryAfterSeconds = 0. */
  public record Decision(boolean allowed, long retryAfterSeconds, String window) {
    static Decision ok() {
      return new Decision(true, 0, "");
    }
  }

  /** 오늘(서울) 0시. */
  public static Instant dayStart(Instant now) {
    return LocalDate.ofInstant(now, SEOUL).atStartOfDay(SEOUL).toInstant();
  }

  public static Instant minuteStart(Instant now) {
    return now.minus(Duration.ofMinutes(1));
  }

  /**
   * @param lastMinute 지난 1분 동안 연 질문 수
   * @param earliestInLastMinute 그중 가장 이른 시각(없으면 null) — 그 시각 + 1분이면 한 자리가 빈다
   * @param today 오늘(서울) 연 질문 수
   */
  public Decision decide(
      Instant now,
      int lastMinute,
      @org.jspecify.annotations.Nullable Instant earliestInLastMinute,
      int today) {
    if (today >= perDay) {
      Instant tomorrow =
          LocalDate.ofInstant(now, SEOUL).plusDays(1).atStartOfDay(SEOUL).toInstant();
      return new Decision(false, Math.max(1, Duration.between(now, tomorrow).toSeconds()), "DAY");
    }
    if (lastMinute >= perMinute) {
      long wait =
          earliestInLastMinute == null
              ? 60
              : Duration.between(now, earliestInLastMinute.plus(Duration.ofMinutes(1))).toSeconds();
      return new Decision(false, Math.max(1, wait), "MINUTE");
    }
    return Decision.ok();
  }
}
