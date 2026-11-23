package com.pmart.customer.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.customer.application.RestrictionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * case-service 사건 구독(platform.case.v1, 2026-11-23 CU-7) — CustomerProfanityDetected 만 반영(7일 정지).
 */
@Configuration(proxyBeanMethods = false)
class CaseEventConsumers {

  /**
   * platform.case.v1 — 바인딩 caseEvents-in-0(설정 저장소). 바인딩이 설정에 있을 때만 만든다 — 설정에 없는 함수 빈은 기동을 멈추므로,
   * 설정보다 이미지가 먼저 올라가도(또는 그 반대로) 깨지지 않게.
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      "spring.cloud.stream.bindings.caseEvents-in-0.destination")
  PmartEventConsumer caseEvents(EventConsumers consumers, RestrictionService restrictions) {
    return consumers.of(restrictions::onCaseEvent);
  }
}
