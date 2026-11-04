package com.pmart.product.content.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.product.content.application.ProductProjectionApplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * product-service 의 상품 이벤트 구독(토픽 commerce.product.v1, 키 productId). ProductVersionPublished 만 반영하고,
 * 같은 토픽의 다른 사건(이 서비스가 내는 ProductContentPublished 포함)은 inbox 에만 남는다.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code <서비스>.yml}, 그룹·재시도·DLQ 는
 * common-messaging 기본값.
 */
@Configuration(proxyBeanMethods = false)
class ProductEventConsumers {

  /** commerce.product.v1 — 바인딩 productEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer productEvents(EventConsumers consumers, ProductProjectionApplier applier) {
    return consumers.of(e -> "ProductVersionPublished".equals(e.eventType()), applier::handle);
  }
}
