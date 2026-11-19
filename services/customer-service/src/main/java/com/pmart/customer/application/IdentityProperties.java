package com.pmart.customer.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.customer.identity.*}(2026-11-20 ADR-0074). rejoin-block = 탈퇴로 풀린 번호를 다른 계정이 다시 확인하지
 * 못하는 기간(사용자 "1달" → P30D, 라이더와 같음). 없으면 번호 확정(가입 단계·/me 확인)이 CONFIG_REQUIRED — 기본값으로 대신하지 않는다.
 */
@ConfigurationProperties(prefix = "pmart.customer.identity")
public record IdentityProperties(@Nullable Duration rejoinBlock) {

  public boolean complete() {
    return rejoinBlock != null && !rejoinBlock.isNegative();
  }
}
