package com.pmart.cart.infrastructure;

import com.pmart.cart.application.CartService;
import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * order-service 이벤트 구독(order.order.v1). OrderConfirmed 만 반영(주문분 장바구니 정리)하고 나머지는 inbox 에만 남는다.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code <서비스>.yml}, 그룹·재시도·DLQ 는
 * common-messaging 기본값.
 */
@Configuration(proxyBeanMethods = false)
class OrderEventConsumers {

  /** order.order.v1 — 바인딩 orderEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer orderEvents(EventConsumers consumers, CartService carts) {
    return consumers.of(
        e -> {
          if ("OrderConfirmed".equals(e.eventType())) {
            carts.applyOrderConfirmed(e);
          }
        });
  }
}
