package com.pmart.cart.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.cart.*}.
 *
 * @param retention 마지막 변경 후 보존 기간(Commerce [채택 설계] 30일). 지나면 새 장바구니
 * @param productBaseUrl product-service(내부 SKU·구매 상한, 서비스 토큰)
 * @param contentBaseUrl product-content-service(공개 카드)
 * @param pricingBaseUrl pricing-service(공개 가격)
 * @param inventoryBaseUrl mfc-inventory-service(내부 가용, 서비스 토큰) — 표시용
 */
@ConfigurationProperties(prefix = "pmart.cart")
public record CartProperties(
    Duration retention,
    String productBaseUrl,
    String contentBaseUrl,
    String pricingBaseUrl,
    String inventoryBaseUrl) {

  public CartProperties {
    retention = retention == null ? Duration.ofDays(30) : retention;
    productBaseUrl = blank(productBaseUrl) ? "http://product-service:8101" : productBaseUrl;
    contentBaseUrl = blank(contentBaseUrl) ? "http://product-content-service:8102" : contentBaseUrl;
    pricingBaseUrl = blank(pricingBaseUrl) ? "http://pricing-service:8107" : pricingBaseUrl;
    inventoryBaseUrl =
        blank(inventoryBaseUrl) ? "http://mfc-inventory-service:8301" : inventoryBaseUrl;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
