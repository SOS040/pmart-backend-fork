package com.pmart.order.orchestration.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.order.orchestration.application.CancellationSagaService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * claim-service 사건 구독(order.claim.v1). CancellationRequested 로 S3 를 시작하고 나머지는 inbox 에만 남는다.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code <서비스>.yml}, 그룹·재시도·DLQ 는
 * common-messaging 기본값.
 */
@Configuration(proxyBeanMethods = false)
class ClaimEventConsumers {

  /** order.claim.v1 — 바인딩 claimEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer claimEvents(EventConsumers consumers, CancellationSagaService sagas) {
    return consumers.of(sagas::onClaimEvent);
  }
}
