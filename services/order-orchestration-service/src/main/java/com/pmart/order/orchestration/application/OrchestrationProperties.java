package com.pmart.order.orchestration.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * {@code pmart.orchestration.*}. 참여 정본 주소(모두 {@code /internal}).
 *
 * @param stuckAfter RUNNING·COMPENSATING 사가를 멈춘 것으로 보고 재개할 시간(같은 키 재전송이라 안전)
 *     <p>실행 중 갱신(ADR-0033): {@code @RefreshScope} — Bus 갱신 뒤 다음 사용 때 새 값으로 다시 만든다(record 는 final 이라
 *     refresh 프록시를 못 만들어 클래스). 쓰는 쪽은 호출마다 읽는다. 주소(*BaseUrl)는 클라이언트를 만들 때 한 번 읽으므로 바꾸면 재시작.
 */
@RefreshScope
@ConfigurationProperties(prefix = "pmart.orchestration")
public class OrchestrationProperties {

  private final String mfcInventoryBaseUrl;
  private final String mfcCapacityBaseUrl;
  private final String deliveryCapacityBaseUrl;
  private final String paymentBaseUrl;
  private final String orderBaseUrl;
  private final String mfcFulfillmentBaseUrl;
  private final @Nullable Duration stuckAfter;

  public OrchestrationProperties(
      String mfcInventoryBaseUrl,
      String mfcCapacityBaseUrl,
      String deliveryCapacityBaseUrl,
      String paymentBaseUrl,
      String orderBaseUrl,
      String mfcFulfillmentBaseUrl,
      @Nullable Duration stuckAfter) {
    mfcInventoryBaseUrl = or(mfcInventoryBaseUrl, "http://mfc-inventory-service:8301");
    mfcCapacityBaseUrl = or(mfcCapacityBaseUrl, "http://mfc-capacity-service:8303");
    deliveryCapacityBaseUrl = or(deliveryCapacityBaseUrl, "http://delivery-capacity-service:8402");
    paymentBaseUrl = or(paymentBaseUrl, "http://payment-service:8205");
    orderBaseUrl = or(orderBaseUrl, "http://order-service:8201");
    mfcFulfillmentBaseUrl = or(mfcFulfillmentBaseUrl, "http://mfc-fulfillment-service:8302");
    stuckAfter = stuckAfter == null ? Duration.ofSeconds(30) : stuckAfter;
    this.mfcInventoryBaseUrl = mfcInventoryBaseUrl;
    this.mfcCapacityBaseUrl = mfcCapacityBaseUrl;
    this.deliveryCapacityBaseUrl = deliveryCapacityBaseUrl;
    this.paymentBaseUrl = paymentBaseUrl;
    this.orderBaseUrl = orderBaseUrl;
    this.mfcFulfillmentBaseUrl = mfcFulfillmentBaseUrl;
    this.stuckAfter = stuckAfter;
  }

  public String mfcInventoryBaseUrl() {
    return mfcInventoryBaseUrl;
  }

  public String mfcCapacityBaseUrl() {
    return mfcCapacityBaseUrl;
  }

  public String deliveryCapacityBaseUrl() {
    return deliveryCapacityBaseUrl;
  }

  public String paymentBaseUrl() {
    return paymentBaseUrl;
  }

  public String orderBaseUrl() {
    return orderBaseUrl;
  }

  public String mfcFulfillmentBaseUrl() {
    return mfcFulfillmentBaseUrl;
  }

  public @Nullable Duration stuckAfter() {
    return stuckAfter;
  }

  private static String or(@Nullable String v, String fallback) {
    return v == null || v.isBlank() ? fallback : v;
  }
}
