package com.pmart.cases.infrastructure;

import com.pmart.cases.application.CaseInboxService;
import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 사건함 구독(2026-11-16, 설정 저장소 {@code case-service.yml} 의 바인딩). 처음 켤 때 소비 그룹 시작 위치는 latest(지난 도움요청은
 * delivery-execution 의 임시 처리함에 남아 있다).
 *
 * <ul>
 *   <li>{@code deliveryEvents-in-0} = delivery.delivery.v1 — {@code DeliveryIncidentRaised} 만(R-4).
 *       같은 토픽의 다른 배송 사건은 버린다.
 *   <li>{@code trackingEvents-in-0} = delivery.tracking.v1 — {@code RiderLocationStale} 만(R-6).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class CaseEventConsumers {

  @Bean
  PmartEventConsumer deliveryEvents(EventConsumers consumers, CaseInboxService cases) {
    return consumers.of(
        e -> "DeliveryIncidentRaised".equals(e.eventType()), cases::onDeliveryIncident);
  }

  @Bean
  PmartEventConsumer trackingEvents(EventConsumers consumers, CaseInboxService cases) {
    return consumers.of(e -> "RiderLocationStale".equals(e.eventType()), cases::onLocationStale);
  }
}
