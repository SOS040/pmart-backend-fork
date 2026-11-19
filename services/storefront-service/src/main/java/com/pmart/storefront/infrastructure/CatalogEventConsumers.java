package com.pmart.storefront.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.storefront.application.DisplayProjector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 상품(commerce.product.v1)·가격(commerce.pricing.v1) 사건 구독 → 진열 투영.
 *
 * <p>Spring Cloud Stream 함수 바인딩(ADR-0035): 토픽·함수 이름은 설정 저장소 {@code <서비스>.yml}, 그룹·재시도·DLQ 는
 * common-messaging 기본값.
 */
@Configuration(proxyBeanMethods = false)
class CatalogEventConsumers {

  /** commerce.product.v1,commerce.pricing.v1 — 바인딩 catalogEvents-in-0(설정 저장소). */
  @Bean
  PmartEventConsumer catalogEvents(EventConsumers consumers, DisplayProjector projector) {
    return consumers.of(projector::apply);
  }

  /**
   * mfc.sku-handling.v1 — 바인딩 handlingEvents-in-0(설정 저장소, 2026-11-19 BR-49). 새 그룹은 처음부터(earliest)
   * 읽어 사본을 채운다.
   */
  @Bean
  PmartEventConsumer handlingEvents(EventConsumers consumers, DisplayProjector projector) {
    return consumers.of(projector::applyHandling);
  }

  /** commerce.review.v1 — 바인딩 reviewEvents-in-0(2026-11-19 BR-63, 진열 평점). 새 그룹은 처음부터 읽어 채운다. */
  @Bean
  PmartEventConsumer reviewEvents(EventConsumers consumers, DisplayProjector projector) {
    return consumers.of(
        e -> "ReviewPublished".equals(e.eventType()) || "ReviewDeleted".equals(e.eventType()),
        projector::applyReview);
  }

  /** order.order.v1 — 바인딩 orderEvents-in-0(2026-11-19 ADR-0072, 인기순 판매 원장). 새 그룹은 처음부터 읽어 채운다. */
  @Bean
  PmartEventConsumer orderEvents(
      EventConsumers consumers, com.pmart.storefront.application.RankingSignals ranking) {
    return consumers.of(
        e ->
            "OrderConfirmed".equals(e.eventType())
                || "OrderLineQuantityChanged".equals(e.eventType()),
        ranking::applyOrder);
  }
}
