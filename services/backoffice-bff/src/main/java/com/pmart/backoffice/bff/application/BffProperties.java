package com.pmart.backoffice.bff.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.bff.*} — 화면 조회가 읽는 정본 서비스 주소. 기본 = 서비스 이름(LAN 은 Eureka·extra_hosts, 운영은 Service
 * DNS).
 *
 * @param approvalBaseUrl approval-service(승인함)
 * @param orchestrationBaseUrl order-orchestration-service(처리 대기 — 멈춘 사가·수동 조치, 2026-11-16 BO-12)
 * @param orderBaseUrl order-service(처리 대기 — 이행 확인 필요, 2026-11-16)
 */
@ConfigurationProperties(prefix = "pmart.bff")
public record BffProperties(
    @Nullable String approvalBaseUrl,
    @Nullable String orchestrationBaseUrl,
    @Nullable String orderBaseUrl) {

  public BffProperties {
    approvalBaseUrl = or(approvalBaseUrl, "http://approval-service:8505");
    orchestrationBaseUrl = or(orchestrationBaseUrl, "http://order-orchestration-service:8202");
    orderBaseUrl = or(orderBaseUrl, "http://order-service:8201");
  }

  private static String or(@Nullable String v, String fallback) {
    return v == null || v.isBlank() ? fallback : v;
  }
}
