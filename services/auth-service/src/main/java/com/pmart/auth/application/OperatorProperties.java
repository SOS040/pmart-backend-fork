package com.pmart.auth.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.auth.operator.*} — 운영자 로그인(ADR-0018). 수치는 보안 설정값이며 업무 정책이 아니다.
 *
 * @param accessTokenTtl access token 수명
 * @param refreshTokenTtl refresh token 수명(쓸 때마다 새로 받는다)
 * @param sessionMaxAge 세션 절대 수명. 갱신해도 늘지 않는다 — 지나면 다시 로그인
 * @param maxFailedAttempts 연속 실패 잠금 횟수
 * @param lockDuration 잠금 시간
 * @param passwordMinLength 비밀번호 최소 길이
 * @param adminRole 계정 관리 역할
 * @param bootstrap 첫 관리자 계정(없을 때만 만든다). 비밀번호는 해시만 받는다(Secret Manager)
 */
@ConfigurationProperties(prefix = "pmart.auth.operator")
public record OperatorProperties(
    Duration accessTokenTtl,
    Duration refreshTokenTtl,
    Duration sessionMaxAge,
    int maxFailedAttempts,
    Duration lockDuration,
    int passwordMinLength,
    String adminRole,
    @Nullable Bootstrap bootstrap) {

  public OperatorProperties {
    accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
    refreshTokenTtl = refreshTokenTtl == null ? Duration.ofHours(12) : refreshTokenTtl;
    sessionMaxAge = sessionMaxAge == null ? Duration.ofHours(12) : sessionMaxAge;
    maxFailedAttempts = maxFailedAttempts <= 0 ? 5 : maxFailedAttempts;
    lockDuration = lockDuration == null ? Duration.ofMinutes(15) : lockDuration;
    passwordMinLength = passwordMinLength <= 0 ? 10 : passwordMinLength;
    adminRole = adminRole == null || adminRole.isBlank() ? "OPERATOR_ADMIN" : adminRole;
  }

  /**
   * @param loginId 비어 있으면 만들지 않는다
   * @param passwordHash {@code {bcrypt}...}
   * @param displayName 표시 이름
   */
  public record Bootstrap(
      @Nullable String loginId, @Nullable String passwordHash, @Nullable String displayName) {}
}
