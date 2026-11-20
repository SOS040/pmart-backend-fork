package com.pmart.customer.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.customer.identity.*}(2026-11-20 ADR-0074). rejoin-block = 탈퇴로 풀린 번호를 다른 계정이 다시 확인하지
 * 못하는 기간(사용자 "1달" → P30D, 라이더와 같음). 없으면 번호 확정(가입 단계·/me 확인)이 CONFIG_REQUIRED — 기본값으로 대신하지 않는다.
 * signup-key-secret(2026-11-21 ADR-0082 5단계) = 가입 쿠폰 중복 방지 키(번호의 HMAC — 되돌릴 수 없음)의 비밀값. 없으면 사건에 키를
 * 싣지 않는다(가입 쿠폰이 나가지 않음).
 */
@ConfigurationProperties(prefix = "pmart.customer.identity")
public record IdentityProperties(@Nullable Duration rejoinBlock, @Nullable String signupKeySecret) {

  public boolean complete() {
    return rejoinBlock != null && !rejoinBlock.isNegative();
  }
}
