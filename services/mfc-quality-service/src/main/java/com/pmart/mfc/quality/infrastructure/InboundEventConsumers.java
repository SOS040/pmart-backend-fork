package com.pmart.mfc.quality.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.quality.application.ReturnInspectionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mfc-inbound 사건(mfc.inbound.v1, 바인딩 inboundEvents-in-0) — ReturnItemReceived → 회수품 반입 사본(IV-10).
 */
@Configuration(proxyBeanMethods = false)
class InboundEventConsumers {

  @Bean
  PmartEventConsumer inboundEvents(EventConsumers consumers, ReturnInspectionService returns) {
    return consumers.of(returns::onInboundEvent);
  }
}
