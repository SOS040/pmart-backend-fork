package com.pmart.pricing.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code pmart.pricing.*} — 가격 일괄 등록이 원천 키로 SKU 를 찾는 product-service 주소(Service DNS). */
@ConfigurationProperties(prefix = "pmart.pricing")
public record PricingProperties(String productBaseUrl) {

  public PricingProperties {
    productBaseUrl =
        productBaseUrl == null || productBaseUrl.isBlank()
            ? "http://product-service:8101"
            : productBaseUrl;
  }
}
