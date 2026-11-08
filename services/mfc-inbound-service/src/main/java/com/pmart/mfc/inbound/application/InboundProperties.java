package com.pmart.mfc.inbound.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.inbound.*}.
 *
 * @param mfcInventoryBaseUrl mfc-inventory-service(내부 ConfirmReceipt)
 * @param postInterval 초기 보유 반입 전기 주기(PENDING 기록 재시도 포함)
 * @param replenishmentEnabled 자동 선반 보충 점검(IV-04)을 돌릴지 — 주기는 {@code
 *     pmart.inbound.replenishment-interval}(기본 1분)
 */
@ConfigurationProperties(prefix = "pmart.inbound")
public record InboundProperties(
    String mfcInventoryBaseUrl, Duration postInterval, boolean replenishmentEnabled) {

  public InboundProperties {
    mfcInventoryBaseUrl =
        mfcInventoryBaseUrl == null || mfcInventoryBaseUrl.isBlank()
            ? "http://mfc-inventory-service:8301"
            : mfcInventoryBaseUrl;
    postInterval = postInterval == null ? Duration.ofSeconds(3) : postInterval;
  }
}
