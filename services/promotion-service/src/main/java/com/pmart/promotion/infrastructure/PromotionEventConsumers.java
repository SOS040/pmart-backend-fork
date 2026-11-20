package com.pmart.promotion.infrastructure;

import com.pmart.common.messaging.EventConsumers;
import com.pmart.common.messaging.PmartEventConsumer;
import com.pmart.promotion.application.CompensationCoupons;
import com.pmart.promotion.application.PriceProjection;
import com.pmart.promotion.application.ProductProjection;
import com.pmart.promotion.application.PromotionApprovals;
import com.pmart.promotion.application.SignupCoupons;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 구독(ADR-0035 함수 바인딩 — 토픽은 설정 저장소 promotion-service.yml). */
@Configuration(proxyBeanMethods = false)
class PromotionEventConsumers {

  /** commerce.product.v1 — 바인딩 productEvents-in-0: SKU → 카테고리 사본. */
  @Bean
  PmartEventConsumer productEvents(EventConsumers consumers, ProductProjection projection) {
    return consumers.of(ProductProjection::wanted, projection::apply);
  }

  /** commerce.pricing.v1 — 바인딩 pricingEvents-in-0: SKU 기본가 사본(표시 할인 계산, ADR-0082 4단계). */
  @Bean
  PmartEventConsumer pricingEvents(EventConsumers consumers, PriceProjection projection) {
    return consumers.of(PriceProjection::wanted, projection::apply);
  }

  /** commerce.customer.v1 — 바인딩 customerEvents-in-0: 가입 단계 번호 확정 → 가입 축하 쿠폰(ADR-0082 5단계). */
  @Bean
  PmartEventConsumer customerEvents(EventConsumers consumers, SignupCoupons signup) {
    return consumers.of(SignupCoupons::wanted, e -> signup.apply(e));
  }

  /** order.order.v1 — 바인딩 orderEvents-in-0: 배차 불가 취소 → 보상 쿠폰(CL-5, ADR-0082 5단계). */
  @Bean
  PmartEventConsumer orderEvents(EventConsumers consumers, CompensationCoupons compensation) {
    return consumers.of(CompensationCoupons::wanted, compensation::apply);
  }

  /** platform.approval.v1 — 바인딩 approvalEvents-in-0: PROMOTION·promotion-service 결정만. */
  @Bean
  PmartEventConsumer approvalEvents(EventConsumers consumers, PromotionApprovals approvals) {
    return consumers.of(PromotionApprovals::mine, approvals::handle);
  }
}
