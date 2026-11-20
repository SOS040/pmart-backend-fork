package com.pmart.customer.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.customer.withdrawal.*}(2026-11-20 ADR-0077 고객 탈퇴). 기술 설정값.
 *
 * <ul>
 *   <li>auth-base-url — 재인증 확인(auth {@code POST /internal/customers/{id}/reauthentications}). 없으면
 *       접수·철회 CONFIG_REQUIRED.
 *   <li>order-base-url·claim-base-url — 의무 확인. 없으면 확인을 하지 않고 UNKNOWN 으로 둔다(거래 없음으로 추정하지 않음).
 *   <li>check-interval — 진행 중 탈퇴를 다시 확인하는 주기(없으면 10분).
 * </ul>
 */
@ConfigurationProperties(prefix = "pmart.customer.withdrawal")
public record WithdrawalProperties(
    @Nullable String authBaseUrl,
    @Nullable String orderBaseUrl,
    @Nullable String claimBaseUrl,
    @Nullable Duration checkInterval) {

  public Duration interval() {
    return checkInterval == null || checkInterval.isNegative() || checkInterval.isZero()
        ? Duration.ofMinutes(10)
        : checkInterval;
  }
}
