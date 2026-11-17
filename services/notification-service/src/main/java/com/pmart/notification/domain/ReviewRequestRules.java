package com.pmart.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 리뷰 요청 알림 규칙(2026-11-18 사용자 결정 "A해줘" — 배송 완료 약 1시간 뒤, 주문당 한 번, 아직 리뷰를 안 썼을 때만, 앱 알림함만).
 *
 * <ul>
 *   <li>기록: 고객에게 상품을 건넨 배송 완료(목적 없음·DELIVERY·오배송 다시 보내기)만. 회수 목적 배송·취소된 주문은 기록하지 않는다.
 *   <li>보내기: 완료 + delay 가 지나면 review-service 에 묻는다. 리뷰를 쓴 품목이 하나라도 있으면(삭제 포함) 보내지 않음, 쓸 수 있는 품목이
 *       없으면 보내지 않음, 아니면 보냄. 완료 + maxAge 가 지나도록 못 물었으면 포기(EXPIRED).
 * </ul>
 */
public final class ReviewRequestRules {

  public static final String PENDING = "PENDING";
  public static final String SENT = "SENT";
  public static final String SKIPPED_REVIEWED = "SKIPPED_REVIEWED";
  public static final String SKIPPED_NOT_ELIGIBLE = "SKIPPED_NOT_ELIGIBLE";
  public static final String EXPIRED = "EXPIRED";

  /**
   * 고객이 상품을 받는 배송 목적(DeliveryCreated purpose).
   * 회수(CLAIM_RETURN·MISDELIVERY_RECOVERY·FAILED_DELIVERY_RETURN)는 아니다.
   */
  static final Set<String> CUSTOMER_PURPOSES =
      Set.of("DELIVERY", "MISDELIVERY_RESHIPMENT", "MISDELIVERY_REDELIVERY");

  private ReviewRequestRules() {}

  /** 이 배송 완료로 리뷰 요청을 기록하는가. purpose 가 없으면 일반 배송. 주문 상태를 모르면(null) 막지 않는다. */
  public static boolean records(@Nullable String purpose, @Nullable String orderStatus) {
    if (purpose != null && !CUSTOMER_PURPOSES.contains(purpose)) {
      return false;
    }
    return !"CANCELLED".equals(orderStatus) && !"EXPIRED".equals(orderStatus);
  }

  /** review-service 답으로 정한 결과: SENT(보냄)·SKIPPED_REVIEWED·SKIPPED_NOT_ELIGIBLE. */
  public static String decide(boolean reviewed, int openLines) {
    if (reviewed) {
      return SKIPPED_REVIEWED;
    }
    return openLines > 0 ? SENT : SKIPPED_NOT_ELIGIBLE;
  }

  /** 이 시각 이전에 완료된 PENDING 은 물을 때가 되었다. */
  public static Instant dueBefore(Instant now, Duration delay) {
    return now.minus(delay);
  }

  /** 이 시각 이전에 완료된 PENDING 은 포기한다. */
  public static Instant expiredBefore(Instant now, Duration maxAge) {
    return now.minus(maxAge);
  }

  /** 정책값이 쓸 수 있는가(없거나 음수면 아니다 — 기본값을 지어내지 않는다). */
  public static boolean configured(@Nullable Duration delay, @Nullable Duration maxAge) {
    return delay != null && maxAge != null && !delay.isNegative() && !maxAge.isNegative();
  }
}
