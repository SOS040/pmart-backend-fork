package com.pmart.auth.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * {@code pmart.auth.guest.*} — 비회원 세션(비회원 장바구니용, typ=GUEST). 장바구니 보존(마지막 변경 후 30일)에 맞춰 세션도 30일.
 *
 * @param accessTokenTtl access token 수명
 * @param refreshTokenTtl refresh token 수명(회전)
 * @param sessionMaxAge 세션 절대 수명
 *     <p>실행 중 갱신(ADR-0033): {@code @RefreshScope} — Bus 갱신 뒤 다음 사용 때 새 값으로 다시 만든다(record 는 final 이라
 *     refresh 프록시를 못 만들어 클래스). 쓰는 쪽은 호출마다 읽는다. 주소(*BaseUrl)는 클라이언트를 만들 때 한 번 읽으므로 바꾸면 재시작.
 */
@RefreshScope
@ConfigurationProperties(prefix = "pmart.auth.guest")
public class GuestProperties {

  private final Duration accessTokenTtl;
  private final Duration refreshTokenTtl;
  private final Duration sessionMaxAge;

  public GuestProperties(
      Duration accessTokenTtl, Duration refreshTokenTtl, Duration sessionMaxAge) {
    accessTokenTtl = accessTokenTtl == null ? Duration.ofHours(1) : accessTokenTtl;
    refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(30) : refreshTokenTtl;
    sessionMaxAge = sessionMaxAge == null ? Duration.ofDays(30) : sessionMaxAge;
    this.accessTokenTtl = accessTokenTtl;
    this.refreshTokenTtl = refreshTokenTtl;
    this.sessionMaxAge = sessionMaxAge;
  }

  public Duration accessTokenTtl() {
    return accessTokenTtl;
  }

  public Duration refreshTokenTtl() {
    return refreshTokenTtl;
  }

  public Duration sessionMaxAge() {
    return sessionMaxAge;
  }
}
