package com.pmart.employeeauth.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.employeeauth.application.EmployeeEventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * hr-service 직원 사건 구독(platform.employee.v1 — 바인딩 employeeEvents-in-0, 설정 저장소). 그룹·재시도·DLQ·inbox 는
 * common-messaging 기본값(ADR-0035).
 */
@Configuration(proxyBeanMethods = false)
class EmployeeEventConsumers {

  @Bean
  PmartEventConsumer employeeEvents(EventConsumers consumers, EmployeeEventHandler handler) {
    return consumers.of(e -> EmployeeEventHandler.TYPES.contains(e.eventType()), handler::on);
  }
}
