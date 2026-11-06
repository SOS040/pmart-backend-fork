package com.pmart.order.orchestration.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.order.orchestration.application.FulfillmentSagaService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * order-service 주문 사건 구독(order.order.v1, 2026-11-07 슬라이스 6a). OrderConfirmed 로 S2(이행 요청)를 시작하고 나머지는
 * inbox 에만 남는다.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code <서비스>.yml}, 그룹·재시도·DLQ 는
 * common-messaging 기본값. 새 구독 그룹은 토픽 처음부터 읽으므로 전환 시각({@code
 * pmart.orchestration.fulfillment.start-orders-confirmed-after}) 전 주문은 서비스가 거른다.
 */
@Configuration(proxyBeanMethods = false)
class OrderEventConsumers {

  /** order.order.v1 — 바인딩 orderEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer orderEvents(EventConsumers consumers, FulfillmentSagaService sagas) {
    return consumers.of(sagas::onOrderEvent);
  }
}
