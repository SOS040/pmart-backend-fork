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

  public static @Nullable Step step(String eventType) {
    return STEPS.get(eventType);
  }

  public static boolean customerDelivery(@Nullable String type) {
    return type == null || CUSTOMER_TYPES.contains(type);
  }
}
