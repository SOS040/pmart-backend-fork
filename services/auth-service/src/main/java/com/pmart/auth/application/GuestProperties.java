package com.pmart.auth.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.auth.guest.*} — 비회원 세션(비회원 장바구니용, typ=GUEST). 장바구니 보존(마지막 변경 후 30일)에 맞춰 세션도 30일.
 *
 * @param accessTokenTtl access token 수명
 * @param refreshTokenTtl refresh token 수명(회전)
 * @param sessionMaxAge 세션 절대 수명
 */
@ConfigurationProperties(prefix = "pmart.auth.guest")
public record GuestProperties(
    Duration accessTokenTtl, Duration refreshTokenTtl, Duration sessionMaxAge) {

  public GuestProperties {
    accessTokenTtl = accessTokenTtl == null ? Duration.ofHours(1) : accessTokenTtl;
    refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(30) : refreshTokenTtl;
    sessionMaxAge = sessionMaxAge == null ? Duration.ofDays(30) : sessionMaxAge;
  }
}
