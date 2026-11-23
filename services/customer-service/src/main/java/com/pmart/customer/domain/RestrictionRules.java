package com.pmart.customer.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 고객 계정 제한(CU-7, 2026-11-23 사용자 결정): RESTRICTED 이용제한 = 문의·리뷰 작성 막기, SUSPENDED 정지 = 주문·로그인까지 막기. 늘
 * 기한(7일)이 있고 지나면 NONE(자동 해제). 영구 정지 없음 — 라이더와 따로.
 */
public final class RestrictionRules {

  public static final String NONE = "NONE";
  public static final String RESTRICTED = "RESTRICTED";
  public static final String SUSPENDED = "SUSPENDED";

  private RestrictionRules() {}

  /** 저장 값 → 지금 효력(기한이 지났거나 없으면 NONE). */
  public static String effective(@Nullable String stored, @Nullable Instant until, Instant now) {
    if (stored == null || NONE.equals(stored) || until == null || !until.isAfter(now)) {
      return NONE;
    }
    return stored;
  }

  public static @Nullable Instant effectiveUntil(
      @Nullable String stored, @Nullable Instant until, Instant now) {
    return NONE.equals(effective(stored, until, now)) ? null : until;
  }

  /** 자동 정지가 지금 제한보다 센가 — 같거나 긴 SUSPENDED 면 그대로. RESTRICTED 는 SUSPENDED 로 올린다. */
  public static boolean autoSuspendNeeded(
      String current, @Nullable Instant currentUntil, Instant until) {
    if (SUSPENDED.equals(current)) {
      return currentUntil != null && currentUntil.isBefore(until);
    }
    return true;
  }

  public static boolean blocksPurchase(String effective) {
    return SUSPENDED.equals(effective);
  }
}
