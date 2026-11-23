package com.pmart.auth.infrastructure;

import com.pmart.auth.application.CustomerWithdrawalService;
import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * customer-service 이벤트 구독(commerce.customer.v1, 2026-11-20 ADR-0077). CustomerWithdrawalCompleted 만
 * 반영하고 나머지 사건은 inbox 에도 남기지 않는다.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code auth-service.yml}, 그룹·재시도·DLQ 는
 * common-messaging 기본값.
 */
@Configuration(proxyBeanMethods = false)
class CustomerEventConsumers {

  /** commerce.customer.v1 — 바인딩 customerEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer customerEvents(
      EventConsumers consumers,
      CustomerWithdrawalService withdrawals,
      com.pmart.auth.application.CustomerSuspensions suspensions) {
    return consumers.of(
        e ->
            CustomerWithdrawalService.COMPLETED.equals(e.eventType())
                || com.pmart.auth.application.CustomerSuspensions.EVENT.equals(e.eventType()),
        e -> {
          if (CustomerWithdrawalService.COMPLETED.equals(e.eventType())) {
            withdrawals.onWithdrawalCompleted(e);
          } else {
            suspensions.onRestricted(e); // 2026-11-23 CU-7 고객 정지
          }
        });
  }
}
