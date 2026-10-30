package com.pmart.auth.application;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.auth.customer.*} — 고객 소셜 로그인(D6: Google 먼저, 2026-10-30). 토큰 수명은 보안 설정값.
 *
 * @param accessTokenTtl access token 수명
 * @param refreshTokenTtl refresh token 수명(회전)
 * @param sessionMaxAge 세션 절대 수명(앱 재로그인 주기)
 * @param requireConsent 신규 가입에 필수 약관 동의 단계(AUTH_CONSENT)를 둘지. 사용자 결정(2026-10-30)으로 지금은 false — 동의
 *     기능 (customer-service)이 생기면 켠다
 * @param failureWindow 실패 집계 창(Commerce 4.9: 15분)
 * @param maxFailures 창 안의 실패 한도(5회)
 * @param restriction 제한 시간(10분)
 * @param google Google ID 토큰 검증
 */
@ConfigurationProperties(prefix = "pmart.auth.customer")
public record CustomerProperties(
    Duration accessTokenTtl,
    Duration refreshTokenTtl,
    Duration sessionMaxAge,
    boolean requireConsent,
    Duration failureWindow,
    int maxFailures,
    Duration restriction,
    Google google) {

  public CustomerProperties {
    accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(30) : accessTokenTtl;
    refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(30) : refreshTokenTtl;
    sessionMaxAge = sessionMaxAge == null ? Duration.ofDays(30) : sessionMaxAge;
    failureWindow = failureWindow == null ? Duration.ofMinutes(15) : failureWindow;
    maxFailures = maxFailures <= 0 ? 5 : maxFailures;
    restriction = restriction == null ? Duration.ofMinutes(10) : restriction;
    google = google == null ? new Google(null, null, null) : google;
  }

  /**
   * @param clientIds 허용할 ID 토큰 aud(Google OAuth 클라이언트 ID — 앱·웹 서버 클라이언트). 비어 있으면 Google 로그인은
   *     CONFIG_REQUIRED
   * @param jwkSetUri Google 공개키
   * @param issuers 허용 iss
   */
  public record Google(List<String> clientIds, String jwkSetUri, List<String> issuers) {
    public Google {
      clientIds =
          clientIds == null
              ? List.of()
              : clientIds.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
      jwkSetUri =
          jwkSetUri == null || jwkSetUri.isBlank()
              ? "https://www.googleapis.com/oauth2/v3/certs"
              : jwkSetUri;
      issuers =
          issuers == null || issuers.isEmpty()
              ? List.of("https://accounts.google.com", "accounts.google.com")
              : List.copyOf(issuers);
    }
  }
}
