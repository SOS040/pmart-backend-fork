package com.pmart.review.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.review.application.ReviewService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 구독(설정 저장소 {@code review-service.yml} 의 바인딩). {@code deliveryEvents-in-0} = delivery.delivery.v1 —
 * {@code DeliveryCompleted} 만(리뷰 자격). 같은 토픽의 다른 배송 사건은 버린다. 처음 켤 때 소비 그룹 시작 위치는 earliest(토픽 보관 7일
 * 안의 배송 완료로 자격을 채운다 — 14일 기한은 수령 시각 기준이라 늦게 받아도 맞다).
 */
@Configuration(proxyBeanMethods = false)
class ReviewEventConsumers {

  @Bean
  PmartEventConsumer deliveryEvents(EventConsumers consumers, ReviewService reviews) {
    return consumers.of(
        e -> "DeliveryCompleted".equals(e.eventType()), reviews::onDeliveryCompleted);
  }
}
