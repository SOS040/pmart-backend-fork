package com.pmart.order.orchestration.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * {@code pmart.orchestration.*}. 참여 정본 주소(모두 {@code /internal}).
 *
 * @param stuckAfter RUNNING·COMPENSATING 사가를 멈춘 것으로 보고 재개할 시간(같은 키 재전송이라 안전)
 * @param fulfillment S2 이행 요청(슬라이스 6a). {@code start-orders-confirmed-after} = 이 시각 이후 확정된 주문만 S2 를
 *     시작한다(새 구독 그룹이 토픽 처음부터 읽어 옛 주문까지 오므로 — 없으면 S2 를 시작하지 않는다). {@code retry-window}·{@code
 *     max-new-versions} 는 센터별 운영 설정(D6-5, 키 {@code "[MFC-SEED-01]"}) — 없는 센터는 재시도 대상 거절 때
 *     CONFIG_REQUIRED 보류.
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
  private final String claimBaseUrl;
  private final @Nullable Duration stuckAfter;
  private final Fulfillment fulfillment;

  /** S2 설정. 필드는 모두 선택 — 비면 위 설명대로 보류·미시작. */
  public record Fulfillment(
      @Nullable Instant startOrdersConfirmedAfter,
      @Nullable Map<String, Duration> retryWindow,
      @Nullable Map<String, Integer> maxNewVersions) {

    public @Nullable Duration retryWindow(String mfcId) {
      return retryWindow == null ? null : retryWindow.get(mfcId);
    }

    public @Nullable Integer maxNewVersions(String mfcId) {
      return maxNewVersions == null ? null : maxNewVersions.get(mfcId);
    }
  }

  public OrchestrationProperties(
      String mfcInventoryBaseUrl,
      String mfcCapacityBaseUrl,
      String deliveryCapacityBaseUrl,
      String paymentBaseUrl,
      String orderBaseUrl,
      String mfcFulfillmentBaseUrl,
      String claimBaseUrl,
      @Nullable Duration stuckAfter,
      @Nullable Fulfillment fulfillment) {
    mfcInventoryBaseUrl = or(mfcInventoryBaseUrl, "http://mfc-inventory-service:8301");
    mfcCapacityBaseUrl = or(mfcCapacityBaseUrl, "http://mfc-capacity-service:8303");
    deliveryCapacityBaseUrl = or(deliveryCapacityBaseUrl, "http://delivery-capacity-service:8402");
    paymentBaseUrl = or(paymentBaseUrl, "http://payment-service:8205");
    orderBaseUrl = or(orderBaseUrl, "http://order-service:8201");
    mfcFulfillmentBaseUrl = or(mfcFulfillmentBaseUrl, "http://mfc-fulfillment-service:8302");
    claimBaseUrl = or(claimBaseUrl, "http://claim-service:8204");
    stuckAfter = stuckAfter == null ? Duration.ofSeconds(30) : stuckAfter;
    this.mfcInventoryBaseUrl = mfcInventoryBaseUrl;
    this.mfcCapacityBaseUrl = mfcCapacityBaseUrl;
    this.deliveryCapacityBaseUrl = deliveryCapacityBaseUrl;
    this.paymentBaseUrl = paymentBaseUrl;
    this.orderBaseUrl = orderBaseUrl;
    this.mfcFulfillmentBaseUrl = mfcFulfillmentBaseUrl;
    this.claimBaseUrl = claimBaseUrl;
    this.stuckAfter = stuckAfter;
    this.fulfillment = fulfillment == null ? new Fulfillment(null, null, null) : fulfillment;
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

  public String claimBaseUrl() {
    return claimBaseUrl;
  }

  public Fulfillment fulfillment() {
    return fulfillment;
  }

  public @Nullable Duration stuckAfter() {
    return stuckAfter;
  }

  private static String or(@Nullable String v, String fallback) {
    return v == null || v.isBlank() ? fallback : v;
  }
}
