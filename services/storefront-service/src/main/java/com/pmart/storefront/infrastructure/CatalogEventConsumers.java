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

  /**
   * order.order.v1 — 바인딩 orderEvents-in-0(2026-11-19 ADR-0072, 인기순 판매 원장). 새 그룹은 처음부터 읽어 채운다. 순위
   * 정책값 ({@code pmart.storefront.ranking.sales-window-days})이 있을 때만 만든다 — 바인딩
   * 확인(ConsumerBindingVerifier)이 바인딩 없는 소비자 빈을 기동 실패로 보므로, 설정(바인딩·정책)을 넣기 전에는 이 기능만 꺼진 채로 뜬다.
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      prefix = "pmart.storefront.ranking",
      name = "sales-window-days")
  PmartEventConsumer orderEvents(
      EventConsumers consumers, com.pmart.storefront.application.RankingSignals ranking) {
    return consumers.of(
        e ->
            "OrderConfirmed".equals(e.eventType())
                || "OrderLineQuantityChanged".equals(e.eventType()),
        ranking::applyOrder);
  }

  /**
   * platform.approval.v1 — 바인딩 approvalEvents-in-0(2026-11-20 ADR-0075, 진열 편성 게시).
   * DISPLAY·storefront-service 결정만 inbox 에 남긴다. 바인딩 설정이 있을 때만 만든다(설정을 넣기 전 배포에도 뜨게 — 승인돼도 그때까지는
   * 게시되지 않음).
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "spring.cloud.stream.bindings.approvalEvents-in-0.destination")
  PmartEventConsumer approvalEvents(
      EventConsumers consumers, com.pmart.storefront.application.LayoutPublisher publisher) {
    return consumers.of(com.pmart.storefront.application.LayoutPublisher::mine, publisher::handle);
  }
}
