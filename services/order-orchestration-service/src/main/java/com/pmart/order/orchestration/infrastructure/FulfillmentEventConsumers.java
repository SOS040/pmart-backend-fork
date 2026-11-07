package com.pmart.order.orchestration.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.order.orchestration.application.ShortageSagaService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mfc-fulfillment 사건 구독(mfc.fulfillment.v1, IV-09 2부 ADR-0045). LineShortageReported 로 결품 사가를 시작하고
 * 나머지는 inbox 에만 남는다. 바인딩 {@code fulfillmentEvents-in-0}(설정 저장소).
 */
@Configuration(proxyBeanMethods = false)
class FulfillmentEventConsumers {

  @Bean
  PmartEventConsumer fulfillmentEvents(EventConsumers consumers, ShortageSagaService sagas) {
    return consumers.of(sagas::onFulfillmentEvent);
  }
}
