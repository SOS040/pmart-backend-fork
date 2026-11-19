package com.pmart.order.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.order.application.FulfillmentIssueService;
import com.pmart.order.application.OrderService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mfc-fulfillment 사건 구독(토픽 mfc.fulfillment.v1, 슬라이스 6b). PreparationStarted(→
 * PREPARING)·PackageHandedOver(→ 인계 수량·진행 상태, O-1 2026-11-16)를 반영하고, 같은 토픽의 다른 사건은 반영 없이 inbox 에만
 * 남는다. 반영과 inbox 기록은 한 트랜잭션.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 바인딩 {@code fulfillmentEvents-in-0} 의 토픽은 설정 저장소 {@code
 * order-service.yml}.
 */
@Configuration(proxyBeanMethods = false)
class FulfillmentEventConsumers {

  @Bean
  PmartEventConsumer fulfillmentEvents(EventConsumers consumers, OrderService orders) {
    return consumers.of(
        e -> {
          if ("PreparationStarted".equals(e.eventType())) {
            orders.preparationStarted(e);
          } else if ("PackageHandedOver".equals(e.eventType())) {
            orders.packageHandedOver(e);
          }
        });
  }

  /**
   * delivery-execution 사건(토픽 delivery.delivery.v1, 바인딩 {@code deliveryEvents-in-0}, O-1 2026-11-16)
   * — DeliveryCompleted 만 전달 수량·진행 상태·OrderCompleted 로 반영. DeliveryFailed 등 그 밖은 inbox 에만(실패 처리 규칙은
   * 아직 — 자동 환불 없음).
   */
  @Bean
  PmartEventConsumer deliveryEvents(
      EventConsumers consumers,
      OrderService orders,
      com.pmart.order.application.DeliveryLinks links) {
    return consumers.of(
        e -> {
          // 고객 주문의 배송 짝·상태(2026-11-19 BR-61) — 배송 생성·배정·픽업·전달·실패·픽업 전 취소
          links.on(e);
          if ("DeliveryCompleted".equals(e.eventType())) {
            orders.deliveryCompleted(e);
          }
        });
  }

  /**
   * mfc-inventory 사건(토픽 mfc.inventory.v1, 바인딩 {@code inventoryEvents-in-0}) — ReservationBlocked 만
   * "이행 확인 필요" 로 남긴다 (자동 취소·환불 없음, 2026-11-08). 같은 토픽의 다른 사건은 inbox 에만.
   */
  @Bean
  PmartEventConsumer inventoryEvents(EventConsumers consumers, FulfillmentIssueService issues) {
    return consumers.of(
        e -> {
          if ("ReservationBlocked".equals(e.eventType())) {
            issues.blocked(e);
          } else if ("ReservationBlockCleared".equals(e.eventType())) {
            issues.cleared(e); // 피킹 시작 전 재확보 → 자동 종결(#5 안 C)
          }
        });
  }
}
