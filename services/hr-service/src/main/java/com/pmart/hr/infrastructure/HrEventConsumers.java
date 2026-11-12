package com.pmart.hr.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.hr.application.GrantDecisions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 구독: 직원 권한 승인 결과(platform.approval.v1 — 바인딩 approvalEvents-in-0, type GRANT 만, ADR-0056 5-4). 토픽은
 * 설정 저장소, 그룹·재시도·DLQ·inbox 는 common-messaging 기본값(ADR-0035).
 */
@Configuration(proxyBeanMethods = false)
class HrEventConsumers {

  @Bean
  PmartEventConsumer approvalEvents(EventConsumers consumers, GrantDecisions decisions) {
    return consumers.of(GrantDecisions::grantDecision, decisions::on);
  }
}
