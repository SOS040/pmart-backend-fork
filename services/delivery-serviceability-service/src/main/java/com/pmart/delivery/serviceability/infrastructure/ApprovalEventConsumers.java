package com.pmart.delivery.serviceability.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.delivery.serviceability.application.ServiceabilityService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * approval-service ApprovalDecided 구독(platform.approval.v1). SERVICEABILITY 승인만 반영하고 나머지는 inbox 에만
 * 남는다.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code <서비스>.yml}, 그룹·재시도·DLQ 는
 * common-messaging 기본값.
 */
@Configuration(proxyBeanMethods = false)
class ApprovalEventConsumers {

  /** platform.approval.v1 — 바인딩 approvalEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer approvalEvents(EventConsumers consumers, ServiceabilityService service) {
    return consumers.of(service::applyApproval);
  }
}
