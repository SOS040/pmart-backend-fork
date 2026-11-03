package com.pmart.backoffice.bff.application;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.bff.*} — 화면 조회가 읽는 정본 서비스 주소.
 *
 * @param approvalBaseUrl approval-service(승인함). 기본 서비스 이름(LAN 은 Eureka, 운영은 Service DNS)
 */
@ConfigurationProperties(prefix = "pmart.bff")
public record BffProperties(@Nullable String approvalBaseUrl) {

  public BffProperties {
    approvalBaseUrl =
        approvalBaseUrl == null || approvalBaseUrl.isBlank()
            ? "http://approval-service:8505"
            : approvalBaseUrl;
  }
}
