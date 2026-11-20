package com.pmart.mfc.capacity.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.capacity.application.AssignmentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mfc.fulfillment.v1 구독(바인딩 fulfillmentEvents-in-0, 토픽은 설정 저장소 — 0.32.0, ADR-0080). TaskProgressed
 * 중 PACK 이 피킹 끝으로 READY 가 된 것만(나머지는 inbox 없이 버림) → 피커에게 바로 배정.
 */
@Configuration(proxyBeanMethods = false)
class FulfillmentEventConsumers {

  @Bean
  PmartEventConsumer fulfillmentEvents(EventConsumers consumers, AssignmentService assignments) {
    return consumers.of(AssignmentService::packReleasedToPicker, assignments::assignPackToPicker);
  }
}
