package com.pmart.claim.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * {@code pmart.claim.*}. 의존 정본 주소(모두 {@code /internal}).
 *
 * @param refundRetryAfter 실행 대기·PG 결과 대기·불명 환불을 다시 실행·조회하기까지의 시간(같은 멱등키라 안전)
 *     <p>실행 중 갱신(ADR-0033): {@code @RefreshScope} — Bus 갱신 뒤 다음 사용 때 새 값으로 다시 만든다(record 는 final 이라
 *     refresh 프록시를 못 만들어 클래스). 쓰는 쪽은 호출마다 읽는다. 주소(*BaseUrl)는 클라이언트를 만들 때 한 번 읽으므로 바꾸면 재시작.
 */
@RefreshScope
@ConfigurationProperties(prefix = "pmart.claim")
public class ClaimProperties {

  private final String orderBaseUrl;
  private final String paymentBaseUrl;
  private final @Nullable Duration refundRetryAfter;

  public ClaimProperties(
      String orderBaseUrl, String paymentBaseUrl, @Nullable Duration refundRetryAfter) {
    orderBaseUrl = or(orderBaseUrl, "http://order-service:8201");
    paymentBaseUrl = or(paymentBaseUrl, "http://payment-service:8205");
    refundRetryAfter = refundRetryAfter == null ? Duration.ofSeconds(30) : refundRetryAfter;
    this.orderBaseUrl = orderBaseUrl;
    this.paymentBaseUrl = paymentBaseUrl;
    this.refundRetryAfter = refundRetryAfter;
  }

  public String orderBaseUrl() {
    return orderBaseUrl;
  }

  public String paymentBaseUrl() {
    return paymentBaseUrl;
  }

  public @Nullable Duration refundRetryAfter() {
    return refundRetryAfter;
  }

  private static String or(@Nullable String v, String fallback) {
    return v == null || v.isBlank() ? fallback : v;
  }
}
