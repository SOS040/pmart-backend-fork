package com.pmart.auth.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

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
 * @param devAccounts 개발 환경 전용 계정(2026-11-07, LAN 프로필에만 둔다 — 운영 설정에 넣지 않는다). 개발 중 자동 시험용(예
 *     claude-dev). 없을 때만 만들고, 비밀번호 변경 요구 없음. 없애려면 설정에서 지우고 계정을 비활성화한다
 *     <p>실행 중 갱신(ADR-0033): {@code @RefreshScope} — Bus 갱신 뒤 다음 사용 때 새 값으로 다시 만든다(record 는 final 이라
 *     refresh 프록시를 못 만들어 클래스). 쓰는 쪽은 호출마다 읽는다. 주소(*BaseUrl)는 클라이언트를 만들 때 한 번 읽으므로 바꾸면 재시작.
 */
@RefreshScope
@ConfigurationProperties(prefix = "pmart.auth.operator")
public class OperatorProperties {

  private final Duration accessTokenTtl;
  private final Duration refreshTokenTtl;
  private final Duration sessionMaxAge;
  private final int maxFailedAttempts;
  private final Duration lockDuration;
  private final int passwordMinLength;
  private final String adminRole;
  private final @Nullable Bootstrap bootstrap;
  private final java.util.List<DevAccount> devAccounts;

  public OperatorProperties(
      Duration accessTokenTtl,
      Duration refreshTokenTtl,
      Duration sessionMaxAge,
      int maxFailedAttempts,
      Duration lockDuration,
      int passwordMinLength,
      String adminRole,
      @Nullable Bootstrap bootstrap,
      java.util.@Nullable List<DevAccount> devAccounts) {
    accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
    refreshTokenTtl = refreshTokenTtl == null ? Duration.ofHours(12) : refreshTokenTtl;
    sessionMaxAge = sessionMaxAge == null ? Duration.ofHours(12) : sessionMaxAge;
    maxFailedAttempts = maxFailedAttempts <= 0 ? 5 : maxFailedAttempts;
    lockDuration = lockDuration == null ? Duration.ofMinutes(15) : lockDuration;
    passwordMinLength = passwordMinLength <= 0 ? 10 : passwordMinLength;
    adminRole = adminRole == null || adminRole.isBlank() ? "OPERATOR_ADMIN" : adminRole;
    this.accessTokenTtl = accessTokenTtl;
    this.refreshTokenTtl = refreshTokenTtl;
    this.sessionMaxAge = sessionMaxAge;
    this.maxFailedAttempts = maxFailedAttempts;
    this.lockDuration = lockDuration;
    this.passwordMinLength = passwordMinLength;
    this.adminRole = adminRole;
    this.bootstrap = bootstrap;
    this.devAccounts =
        devAccounts == null ? java.util.List.of() : java.util.List.copyOf(devAccounts);
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

  public int maxFailedAttempts() {
    return maxFailedAttempts;
  }

  public Duration lockDuration() {
    return lockDuration;
  }

  public int passwordMinLength() {
    return passwordMinLength;
  }

  public String adminRole() {
    return adminRole;
  }

  public @Nullable Bootstrap bootstrap() {
    return bootstrap;
  }

  public java.util.List<DevAccount> devAccounts() {
    return devAccounts;
  }

  /**
   * 개발 환경 전용 계정.
   *
   * @param roles 비면 관리자 역할 하나
   * @param centers 센터 권한
   */
  public record DevAccount(
      @Nullable String loginId,
      @Nullable String passwordHash,
      @Nullable String displayName,
      java.util.@Nullable List<String> roles,
      java.util.@Nullable List<String> centers) {}

  /**
   * @param loginId 비어 있으면 만들지 않는다
   * @param passwordHash {@code {bcrypt}...}
   * @param displayName 표시 이름
   */
  public record Bootstrap(
      @Nullable String loginId, @Nullable String passwordHash, @Nullable String displayName) {}
}
