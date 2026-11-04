package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.mfc.inbound.application.InitialStockService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * approval-service ApprovalDecided 구독(platform.approval.v1). INITIAL_STOCK 승인만 반입 기록으로 만든다. 반영과
 * inbox 기록이 한 트랜잭션이고, mfc-inventory 전기는 트랜잭션 밖 주기 작업이 한다.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code <서비스>.yml}, 그룹·재시도·DLQ 는
 * common-messaging 기본값.
 */
@Configuration(proxyBeanMethods = false)
class ApprovalEventConsumers {

  /** platform.approval.v1 — 바인딩 approvalEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer approvalEvents(EventConsumers consumers, InitialStockService loads) {
    return consumers.of(loads::applyApproval);
  }
}
