package com.pmart.storefront.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.storefront.*}.
 *
 * @param contentBaseUrl product-content-service 주소 — 콘텐츠 사건에 없는 상품명·대표 이미지를 공개 카드 API 로 보강
 */
@ConfigurationProperties(prefix = "pmart.storefront")
public record StorefrontProperties(String contentBaseUrl) {

  public StorefrontProperties {
    contentBaseUrl =
        contentBaseUrl == null || contentBaseUrl.isBlank()
            ? "http://product-content-service:8102"
            : contentBaseUrl;
  }
}
