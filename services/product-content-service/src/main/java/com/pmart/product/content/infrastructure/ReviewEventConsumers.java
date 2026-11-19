package com.pmart.product.content.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.product.content.application.ReviewRatingProjector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** review 사건 구독(토픽 commerce.review.v1, 바인딩 reviewEvents-in-0 — 설정 저장소, 2026-11-19). 별점 집계 사본만. */
@Configuration(proxyBeanMethods = false)
class ReviewEventConsumers {

  @Bean
  PmartEventConsumer reviewEvents(EventConsumers consumers, ReviewRatingProjector projector) {
    return consumers.of(
        e -> "ReviewPublished".equals(e.eventType()) || "ReviewDeleted".equals(e.eventType()),
        projector::handle);
  }
}
