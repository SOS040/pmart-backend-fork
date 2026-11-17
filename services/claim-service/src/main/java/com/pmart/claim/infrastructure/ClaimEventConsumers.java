package com.pmart.claim.infrastructure;

import com.pmart.claim.application.ClaimService;
import com.pmart.claim.application.RefundService;
import com.pmart.claim.application.ReturnFeeService;
import com.pmart.claim.application.ReturnService;
import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 취소·반품 흐름 구독. order.order.v1(OrderCancelled → 취소 확정·환불 의도), mfc.fulfillment.v1(WorkStopResult →
 * 인계됨이면 취소 불가), order.payment.v1(RefundResult → 환불 결과, 반품 회수비 결제 승인). 반품(CL-1, 2026-11-17):
 * delivery.delivery.v1(고객 반품 회수 배송 생성·수거·반납·실패), mfc.inbound.v1(ReturnItemReceived),
 * mfc.quality.v1(ReturnInspected). 나머지 사건은 inbox 에만 남는다.
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

  /** order.payment.v1 — 바인딩 paymentEvents-in-0(설정 저장소). 환불 결과와 반품 회수비 결제(자기 결제 시도만). */
  @Bean
  PmartEventConsumer paymentEvents(
      EventConsumers consumers, RefundService refunds, ReturnFeeService fees) {
    return consumers.of(
        e -> {
          refunds.onPaymentEvent(e);
          fees.onPaymentEvent(e);
        });
  }

  /** delivery.delivery.v1 — 바인딩 deliveryEvents-in-0(설정 저장소, 2026-11-17). 고객 반품 회수 배송만. */
  @Bean
  PmartEventConsumer deliveryEvents(EventConsumers consumers, ReturnService returns) {
    return consumers.of(returns::onDeliveryEvent);
  }

  /** mfc.inbound.v1 — 바인딩 inboundEvents-in-0(설정 저장소, 2026-11-17). ReturnItemReceived. */
  @Bean
  PmartEventConsumer inboundEvents(EventConsumers consumers, ReturnService returns) {
    return consumers.of(returns::onInboundEvent);
  }

  /** mfc.quality.v1 — 바인딩 qualityEvents-in-0(설정 저장소, 2026-11-17). ReturnInspected. */
  @Bean
  PmartEventConsumer qualityEvents(EventConsumers consumers, ReturnService returns) {
    return consumers.of(returns::onQualityEvent);
  }
}
