package com.pmart.mfc.fulfillment.application;

import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * {@code pmart.fulfillment.*}.
 *
 * @param pickLeadTime 센터별 예약 주문 PICK 준비 시각 = 슬롯 시작 − 이 값(D6-3, 센터 운영 설정). 키는 센터 ID — 설정 파일에서 {@code
 *     "[MFC-SEED-01]": PT90M}. 없는 센터는 PICK 만 CONFIG_REQUIRED 대기(0·즉시로 대체하지 않음).
 *     <p>실행 중 갱신(ADR-0033): {@code @RefreshScope} 클래스 — 쓰는 쪽은 호출마다 읽는다. 주소(*BaseUrl)는 클라이언트를 만들 때 한
 *     번 읽으므로 바꾸면 재시작.
 */
@RefreshScope
@ConfigurationProperties(prefix = "pmart.fulfillment")
public class FulfillmentProperties {

  private final String mfcInventoryBaseUrl;
  private final String mfcCapacityBaseUrl;
  private final Map<String, Duration> pickLeadTime;
  private final Map<String, Duration> handoverFenceTtl;

  /**
   * @param handoverFenceTtl 센터별 라이더 인계 허가(fence) 유효시간(ADR-0047, 2026-11-08 사용자 결정 "설정값, 기본 없음"). 없는
   *     센터는 인계 허가를 422 CONFIG_REQUIRED 로 막는다.
   */
  public FulfillmentProperties(
      @Nullable String mfcInventoryBaseUrl,
      @Nullable String mfcCapacityBaseUrl,
      @Nullable Map<String, Duration> pickLeadTime,
      @Nullable Map<String, Duration> handoverFenceTtl) {
    this.handoverFenceTtl = handoverFenceTtl == null ? Map.of() : Map.copyOf(handoverFenceTtl);
    this.mfcInventoryBaseUrl =
        mfcInventoryBaseUrl == null || mfcInventoryBaseUrl.isBlank()
            ? "http://mfc-inventory-service:8301"
            : mfcInventoryBaseUrl;
    this.mfcCapacityBaseUrl =
        mfcCapacityBaseUrl == null || mfcCapacityBaseUrl.isBlank()
            ? "http://mfc-capacity-service:8303"
            : mfcCapacityBaseUrl;
    this.pickLeadTime = pickLeadTime == null ? Map.of() : Map.copyOf(pickLeadTime);
  }

  public String mfcInventoryBaseUrl() {
    return mfcInventoryBaseUrl;
  }

  /** 배정 조회(6b 작업 시작 판정). */
  public String mfcCapacityBaseUrl() {
    return mfcCapacityBaseUrl;
  }

  /** 센터 인계 허가 유효시간. 없으면 null(CONFIG_REQUIRED). */
  public @Nullable Duration handoverFenceTtl(String mfcId) {
    return handoverFenceTtl.get(mfcId);
  }

  /** 센터 준비 시각 설정. 없으면 null(CONFIG_REQUIRED). */
  public @Nullable Duration pickLeadTime(String mfcId) {
    return pickLeadTime.get(mfcId);
  }
}
