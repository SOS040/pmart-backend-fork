package com.pmart.cart.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * {@code pmart.cart.*}.
 *
 * <p>실행 중 갱신(ADR-0033): {@code @RefreshScope} — 설정 저장소가 바뀌어 Bus 갱신이 오면 다음 사용 때 새 값으로 다시 만든다. record
 * 는 final 이라 refresh 프록시(CGLIB)를 만들 수 없어 클래스로 둔다. 쓰는 쪽은 호출마다 {@code props.retention()} 을 읽어 새 값이 바로
 * 적용된다. 주소(*BaseUrl)는 RestClient 를 만들 때 한 번 읽으므로 바꾸면 재시작한다.
 */
@RefreshScope
@ConfigurationProperties(prefix = "pmart.cart")
public class CartProperties {

  /** 마지막 변경 후 보존 기간(Commerce [채택 설계] 30일). 지나면 새 장바구니. 실행 중 갱신된다 */
  private final Duration retention;

  private final String productBaseUrl;
  private final String contentBaseUrl;
  private final String pricingBaseUrl;
  private final String inventoryBaseUrl;

  /**
   * @param retention 마지막 변경 후 보존 기간
   * @param productBaseUrl product-service(내부 SKU·구매 상한)
   * @param contentBaseUrl product-content-service(공개 카드)
   * @param pricingBaseUrl pricing-service(공개 가격)
   * @param inventoryBaseUrl mfc-inventory-service(내부 가용) — 표시용
   */
  public CartProperties(
      @Nullable Duration retention,
      @Nullable String productBaseUrl,
      @Nullable String contentBaseUrl,
      @Nullable String pricingBaseUrl,
      @Nullable String inventoryBaseUrl) {
    this.retention = retention == null ? Duration.ofDays(30) : retention;
    this.productBaseUrl = orDefault(productBaseUrl, "http://product-service:8101");
    this.contentBaseUrl = orDefault(contentBaseUrl, "http://product-content-service:8102");
    this.pricingBaseUrl = orDefault(pricingBaseUrl, "http://pricing-service:8107");
    this.inventoryBaseUrl = orDefault(inventoryBaseUrl, "http://mfc-inventory-service:8301");
  }

  public Duration retention() {
    return retention;
  }

  public String productBaseUrl() {
    return productBaseUrl;
  }

  public String contentBaseUrl() {
    return contentBaseUrl;
  }

  public String pricingBaseUrl() {
    return pricingBaseUrl;
  }

  public String inventoryBaseUrl() {
    return inventoryBaseUrl;
  }

  private static String orDefault(@Nullable String s, String fallback) {
    return s == null || s.isBlank() ? fallback : s;
  }
}
