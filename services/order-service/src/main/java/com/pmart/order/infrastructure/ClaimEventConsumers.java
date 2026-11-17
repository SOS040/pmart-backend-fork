package com.pmart.order.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.order.application.OrderService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * claim-service 사건 구독(토픽 order.claim.v1, 바인딩 {@code claimEvents-in-0} — O-7, 2026-11-17).
 * ClaimClosed 의 반품 종결(RETURN_REFUNDED·RETURN_SHORT_CLOSED)만 주문 행 반품 수량(returnedQty)으로 반영하고, 같은 토픽의
 * 다른 사건은 반영 없이 inbox 에만 남는다. 반영과 inbox 기록은 한 트랜잭션.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽은 설정 저장소 {@code order-service.yml}.
 */
@Configuration(proxyBeanMethods = false)
class ClaimEventConsumers {

  @Bean
  PmartEventConsumer claimEvents(EventConsumers consumers, OrderService orders) {
    return consumers.of(
        e -> {
          if ("ClaimClosed".equals(e.eventType())) {
            orders.claimClosed(e);
          }
        });
  }
}
