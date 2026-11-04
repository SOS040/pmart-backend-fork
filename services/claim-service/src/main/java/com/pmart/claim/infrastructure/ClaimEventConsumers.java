package com.pmart.claim.infrastructure;

import com.pmart.claim.application.ClaimService;
import com.pmart.claim.application.RefundService;
import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 취소 흐름 구독. order.order.v1(OrderCancelled → 취소 확정·환불 의도), mfc.fulfillment.v1(WorkStopResult → 인계됨이면
 * 취소 불가), order.payment.v1(RefundResult → 환불 결과). 나머지 사건은 inbox 에만 남는다.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code <서비스>.yml}, 그룹·재시도·DLQ 는
 * common-messaging 기본값.
 */
@Configuration(proxyBeanMethods = false)
class ClaimEventConsumers {

  /** order.order.v1 — 바인딩 orderEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer orderEvents(EventConsumers consumers, ClaimService claims) {
    return consumers.of(claims::onOrderEvent);
  }

  /** mfc.fulfillment.v1 — 바인딩 fulfillmentEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer fulfillmentEvents(EventConsumers consumers, ClaimService claims) {
    return consumers.of(claims::onFulfillmentEvent);
  }

  /** order.payment.v1 — 바인딩 paymentEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer paymentEvents(EventConsumers consumers, RefundService refunds) {
    return consumers.of(refunds::onPaymentEvent);
  }
}
