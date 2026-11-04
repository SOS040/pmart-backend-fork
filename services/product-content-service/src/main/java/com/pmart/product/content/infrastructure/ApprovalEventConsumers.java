package com.pmart.product.content.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.product.content.application.ContentApprovalApplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * approval-service 의 ApprovalDecided 구독(토픽 platform.approval.v1). 업무 반영과 inbox 기록이 한 트랜잭션이고, 리스너가
 * 정상 반환한 뒤 offset 을 커밋한다(ADR-0009). DISPLAY·이 서비스 대상이 아닌 승인은 반영 없이 inbox 에만 남는다.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code <서비스>.yml}, 그룹·재시도·DLQ 는
 * common-messaging 기본값.
 */
@Configuration(proxyBeanMethods = false)
class ApprovalEventConsumers {

  /** platform.approval.v1 — 바인딩 approvalEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer approvalEvents(EventConsumers consumers, ContentApprovalApplier applier) {
    return consumers.of(applier::handle);
  }
}
