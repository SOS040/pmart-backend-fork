package com.pmart.order.domain;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 고객 주문의 배송 상태(2026-11-19, BR-61). 배송 사건 → 계약 Shipment.state 와 순위(앞으로만). 고객에게 가는
 * 배송만(FORWARD·RESHIPMENT — 회수 RETURN 은 고객 주문의 배송이 아니다).
 */
public final class DeliveryLinkRules {

  private DeliveryLinkRules() {}

  public static final Set<String> CUSTOMER_TYPES = Set.of("FORWARD", "RESHIPMENT");

  /** 사건 → (상태, 순위). 끝 상태는 모두 같은 순위(먼저 온 끝 상태가 남는다). */
  public record Step(String state, int rank) {}

  private static final Map<String, Step> STEPS =
      Map.of(
          "DeliveryCreated", new Step("READY", 1),
          "DeliveryAssigned", new Step("READY", 1),
          "PickupCompleted", new Step("IN_DELIVERY", 2),
          "DeliveryCompleted", new Step("DELIVERED", 3),
          "DeliveryFailed", new Step("FAILED", 3),
          "DeliveryCancelledBeforePickup", new Step("CANCELLED", 3));

  /**
   * 전달 실패 확정 되돌리기(2026-11-19, PC 5 O-10 — DeliveryFailureReverted): FAILED 였던 배송만 다시 진행
   * 중(IN_DELIVERY 2)으로. 순위를 뒤로 돌리는 유일한 사건이다(다른 끝 상태 — 전달·취소 — 는 되돌리지 않는다).
   */
  public static final String FAILURE_REVERTED = "DeliveryFailureReverted";

  public static final Step REVERTED_TO = new Step("IN_DELIVERY", 2);

  public static @Nullable Step step(String eventType) {
    return STEPS.get(eventType);
  }

  public static boolean customerDelivery(@Nullable String type) {
    return type == null || CUSTOMER_TYPES.contains(type);
  }
}
