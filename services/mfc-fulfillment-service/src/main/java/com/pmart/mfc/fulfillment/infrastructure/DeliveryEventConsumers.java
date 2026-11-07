package com.pmart.mfc.fulfillment.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.fulfillment.application.HandoverService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * delivery-dispatch 배송 사건 구독(토픽 delivery.delivery.v1, 슬라이스 6c — ADR-0047).
 * DeliveryCreated·DeliveryAssigned· DeliveryAssignmentChanged·DeliveryCancelledBeforePickup 으로 배송
 * 배정 투영(인계 허가 검사)을 만들고, 반영과 inbox 기록이 한 트랜잭션이다. 바인딩 {@code deliveryEvents-in-0} 의 토픽은 설정 저장소 {@code
 * mfc-fulfillment-service.yml}.
 */
@Configuration(proxyBeanMethods = false)
class DeliveryEventConsumers {

  @Bean
  PmartEventConsumer deliveryEvents(EventConsumers consumers, HandoverService handovers) {
    return consumers.of(handovers::onDeliveryEvent);
  }
}
