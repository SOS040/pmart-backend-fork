package com.pmart.pricing.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.pricing.*} — 가격 일괄 등록이 원천 키로 SKU 를 찾는 product-service 주소(Service DNS), 견적의 기간 할인·쿠폰을
 * 읽는 promotion-service 주소(ADR-0082, 비우면 할인 단계 없이 기본가만 — 쿠폰은 applicable=false·UNAVAILABLE).
 */
@ConfigurationProperties(prefix = "pmart.pricing")
public record PricingProperties(String productBaseUrl, @Nullable String promotionBaseUrl) {

  public PricingProperties {
    productBaseUrl =
        productBaseUrl == null || productBaseUrl.isBlank()
            ? "http://product-service:8101"
            : productBaseUrl;
  }
}
