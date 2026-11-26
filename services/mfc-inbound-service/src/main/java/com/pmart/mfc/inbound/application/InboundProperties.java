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
 * @param cdcInventoryBaseUrl cdc-inventory-service(내부 transit-closures — CDC 이관 입고 종결 알림, MI-5)
 * @param cdcOutboundBaseUrl cdc-outbound-service(내부 transfer-intakes — 승인된 센터 발주를 이관 요청으로,
 *     ADR-0091)
 * @param supplyingCdc 센터 발주를 받을 보급 DC(ADR-0091). 비우면 예전처럼 승인 = 입고 예정(initial_stock_load)
 * @param supplyDueAfter 승인 → 이관 도착 기한(승인 시각 + 이만큼, 기본 1일)
 * @param autoSupplyEnabled 자동 발주 초안(매일, ADR-0091)을 만들지 — 시각은 {@code pmart.inbound.auto-supply-cron}
 */
@ConfigurationProperties(prefix = "pmart.inbound")
public record InboundProperties(
    String mfcInventoryBaseUrl,
    Duration postInterval,
    boolean replenishmentEnabled,
    String cdcInventoryBaseUrl,
    String cdcOutboundBaseUrl,
    String supplyingCdc,
    Duration supplyDueAfter,
    boolean autoSupplyEnabled) {

  public InboundProperties {
    mfcInventoryBaseUrl =
        mfcInventoryBaseUrl == null || mfcInventoryBaseUrl.isBlank()
            ? "http://mfc-inventory-service:8301"
            : mfcInventoryBaseUrl;
    postInterval = postInterval == null ? Duration.ofSeconds(3) : postInterval;
    cdcInventoryBaseUrl =
        cdcInventoryBaseUrl == null || cdcInventoryBaseUrl.isBlank()
            ? "http://cdc-inventory-service:8601"
            : cdcInventoryBaseUrl;
    cdcOutboundBaseUrl =
        cdcOutboundBaseUrl == null || cdcOutboundBaseUrl.isBlank()
            ? "http://cdc-outbound-service:8603"
            : cdcOutboundBaseUrl;
    supplyingCdc = supplyingCdc == null ? "" : supplyingCdc.trim();
    supplyDueAfter = supplyDueAfter == null ? Duration.ofDays(1) : supplyDueAfter;
  }
}
