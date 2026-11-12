package com.pmart.approval.infrastructure;

import com.pmart.approval.application.ApplicationReceiptRecorder;
import com.pmart.approval.application.ApprovalService;
import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 구독: hr-service 직원 권한 요청 GrantRequested(platform.employee.v1 — 같은 토픽의 다른 직원 사건은 버린다, ADR-0056
 * 5-4)와 정본 서비스의 반영 회신 ApplicationReceiptRecorded(platform.approval-receipt.v1, ADR-0039). 반영과 inbox
 * 기록이 한 트랜잭션이고, 리스너가 정상 반환한 뒤 offset 을 커밋한다(ADR-0009).
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code approval-service.yml},
 * 그룹·재시도·DLQ 는 common-messaging 기본값.
 */
@Configuration(proxyBeanMethods = false)
class EventConsumersConfig {

  /** platform.approval-receipt.v1 — 바인딩 receiptEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer receiptEvents(EventConsumers consumers, ApplicationReceiptRecorder receipts) {
    return consumers.of(receipts::handle);
  }

  /** platform.employee.v1 — 바인딩 employeeEvents-in-0(설정 저장소). GrantRequested 만 처리한다. */
  @Bean
  PmartEventConsumer employeeEvents(EventConsumers consumers, ApprovalService approvals) {
    return consumers.of(e -> "GrantRequested".equals(e.eventType()), approvals::intakeGrantRequest);
  }
}
