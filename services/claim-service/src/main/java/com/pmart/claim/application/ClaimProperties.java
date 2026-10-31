package com.pmart.claim.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.claim.*}. 의존 정본 주소(모두 {@code /internal}).
 *
 * @param refundRetryAfter 실행 대기·PG 결과 대기·불명 환불을 다시 실행·조회하기까지의 시간(같은 멱등키라 안전)
 */
@ConfigurationProperties(prefix = "pmart.claim")
public record ClaimProperties(
    String orderBaseUrl, String paymentBaseUrl, @Nullable Duration refundRetryAfter) {

  public ClaimProperties {
    orderBaseUrl = or(orderBaseUrl, "http://order-service:8201");
    paymentBaseUrl = or(paymentBaseUrl, "http://payment-service:8205");
    refundRetryAfter = refundRetryAfter == null ? Duration.ofSeconds(30) : refundRetryAfter;
  }

  private static String or(@Nullable String v, String fallback) {
    return v == null || v.isBlank() ? fallback : v;
  }
}
