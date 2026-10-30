package com.pmart.order.orchestration.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.orchestration.*}. 참여 정본 주소(모두 {@code /internal}, 서비스 토큰).
 *
 * @param stuckAfter RUNNING·COMPENSATING 사가를 멈춘 것으로 보고 재개할 시간(같은 키 재전송이라 안전)
 */
@ConfigurationProperties(prefix = "pmart.orchestration")
public record OrchestrationProperties(
    String mfcInventoryBaseUrl,
    String mfcCapacityBaseUrl,
    String deliveryCapacityBaseUrl,
    String paymentBaseUrl,
    String orderBaseUrl,
    @Nullable Duration stuckAfter) {

  public OrchestrationProperties {
    mfcInventoryBaseUrl = or(mfcInventoryBaseUrl, "http://mfc-inventory-service:8301");
    mfcCapacityBaseUrl = or(mfcCapacityBaseUrl, "http://mfc-capacity-service:8303");
    deliveryCapacityBaseUrl = or(deliveryCapacityBaseUrl, "http://delivery-capacity-service:8402");
    paymentBaseUrl = or(paymentBaseUrl, "http://payment-service:8205");
    orderBaseUrl = or(orderBaseUrl, "http://order-service:8201");
    stuckAfter = stuckAfter == null ? Duration.ofSeconds(30) : stuckAfter;
  }

  private static String or(@Nullable String v, String fallback) {
    return v == null || v.isBlank() ? fallback : v;
  }
}
