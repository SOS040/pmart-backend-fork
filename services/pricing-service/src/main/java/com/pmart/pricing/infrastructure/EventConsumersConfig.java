package com.pmart.pricing.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.pricing.application.PriceApprovalApplier;
import com.pmart.pricing.application.SkuProjector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 구독: approval-service ApprovalDecided(platform.approval.v1), product-service
 * SkuIssued·ProductVersionPublished(commerce.product.v1). 반영과 inbox 기록이 한 트랜잭션이고, 리스너가 정상 반환한 뒤
 * offset 을 커밋한다(ADR-0009). 쓰지 않는 사건은 반영 없이 inbox 에만 남는다.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code <서비스>.yml}, 그룹·재시도·DLQ 는
 * common-messaging 기본값.
 */
@Configuration(proxyBeanMethods = false)
class EventConsumersConfig {

  /** platform.approval.v1 — 바인딩 approvalEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer approvalEvents(EventConsumers consumers, PriceApprovalApplier approvals) {
    return consumers.of(approvals::handle);
  }

  /** commerce.product.v1 — 바인딩 productEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer productEvents(EventConsumers consumers, SkuProjector skus) {
    return consumers.of(skus::apply);
  }
}
