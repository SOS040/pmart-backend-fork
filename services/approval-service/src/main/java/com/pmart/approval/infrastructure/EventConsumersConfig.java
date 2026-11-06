package com.pmart.approval.infrastructure;

import com.pmart.approval.application.ApplicationReceiptRecorder;
import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 구독: 정본 서비스의 반영 회신 ApplicationReceiptRecorded(platform.approval-receipt.v1, ADR-0039). 반영과 inbox
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
}
