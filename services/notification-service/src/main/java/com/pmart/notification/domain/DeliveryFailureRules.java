package com.pmart.notification.domain;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 전달 실패 고객 안내(N-4, 2026-11-18 ADR-0069 — 2026-11-19 사용자 "응"). 사고·파손으로 전달 실패가 확정되면
 * order-orchestration 이 새 상품을 다시 보낸다 — 그 배송에만 "새 상품으로 다시 보내 드려요"를 알린다. 고르는 기준은 order-orchestration
 * {@code ReshipmentRules.reshipsOnFailure} 와 같다(일반 배송, 앞선 재발송 배송 MISDELIVERY_RESHIPMENT). 회수·반납·오배송
 * 다시 배송(원래 상품)과 그 밖 사유(고객 부재 등)는 알리지 않는다 — 상담원이 안내.
 */
public final class DeliveryFailureRules {

  public static final Set<String> RESHIP_REASONS = Set.of("RIDER_INCIDENT", "DAMAGED");

  private DeliveryFailureRules() {}

  public static boolean noticeReshipment(
      @Nullable String deliveryType, @Nullable String purpose, @Nullable String reason) {
    if (reason == null || !RESHIP_REASONS.contains(reason)) {
      return false;
    }
    return "FORWARD".equals(deliveryType)
        || ("RESHIPMENT".equals(deliveryType) && "MISDELIVERY_RESHIPMENT".equals(purpose));
  }
}
