package com.pmart.auth.application;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * {@code pmart.auth.customer.*} — 고객 소셜 로그인(D6: Google 먼저, 2026-10-30). 토큰 수명은 보안 설정값.
 *
 * @param accessTokenTtl access token 수명
 * @param refreshTokenTtl refresh token 수명(회전)
 * @param sessionMaxAge 세션 유휴 수명 — 마지막 갱신(사용)부터 이 기간 안 쓰면 재로그인(2026-11-02, 슬라이딩)
 * @param requireConsent 필수 약관 동의 단계(AUTH_CONSENT)를 둘지 — 켜면 신규 가입과 필수 약관 현행 버전에 동의하지 않은 기존 회원은 동의
 *     화면을 거친다(판단은 customer-service). 서버는 2026-11-17 CU-1 로 준비됐고, LAN 은 고객 앱이 동의 흐름을 반영할 때까지 false.
 *     켤 때는 customer-service {@code consents.purchase-gate} 와 함께 true
 * @param customerServiceBaseUrl customer-service 주소(동의 상태 조회·로그인 중 동의 반영 — 내부 경로)
 * @param failureWindow 실패 집계 창(Commerce 4.9: 15분)
 * @param maxFailures 창 안의 실패 한도(5회)
 * @param restriction 제한 시간(10분)
 * @param google Google ID 토큰 검증
 * @param kakao 카카오 ID 토큰 검증(ADR-0071, 2026-11-19) — client-ids = 앱이 카카오 SDK 초기화에 쓴 앱 키(ID 토큰 aud)
 * @param naver 네이버 ID 토큰 검증(ADR-0071) — client-ids = 네이버 애플리케이션 Client ID(ID 토큰 aud)
 *     <p>실행 중 갱신(ADR-0033): {@code @RefreshScope} — Bus 갱신 뒤 다음 사용 때 새 값으로 다시 만든다(record 는 final 이라
 *     refresh 프록시를 못 만들어 클래스). 쓰는 쪽은 호출마다 읽는다. 주소(*BaseUrl)는 클라이언트를 만들 때 한 번 읽으므로 바꾸면 재시작.
 */
@RefreshScope
@ConfigurationProperties(prefix = "pmart.auth.customer")
public class CustomerProperties {

  private final Duration accessTokenTtl;
  private final Duration refreshTokenTtl;
  private final Duration sessionMaxAge;
  private final boolean requireConsent;
  private final String customerServiceBaseUrl;
  private final Duration failureWindow;
  private final int maxFailures;
  private final Duration restriction;
  private final Google google;
  private final Oidc kakao;
  private final Oidc naver;

  public CustomerProperties(
      Duration accessTokenTtl,
      Duration refreshTokenTtl,
      Duration sessionMaxAge,
      boolean requireConsent,
      @Nullable String customerServiceBaseUrl,
      Duration failureWindow,
      int maxFailures,
      Duration restriction,
      Google google,
      @Nullable Oidc kakao,
      @Nullable Oidc naver) {
    accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(30) : accessTokenTtl;
    refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(30) : refreshTokenTtl;
    sessionMaxAge = sessionMaxAge == null ? Duration.ofDays(30) : sessionMaxAge;
    failureWindow = failureWindow == null ? Duration.ofMinutes(15) : failureWindow;
    maxFailures = maxFailures <= 0 ? 5 : maxFailures;
    restriction = restriction == null ? Duration.ofMinutes(10) : restriction;
    google = google == null ? new Google(null, null, null) : google;
    this.accessTokenTtl = accessTokenTtl;
    this.refreshTokenTtl = refreshTokenTtl;
    this.sessionMaxAge = sessionMaxAge;
    this.requireConsent = requireConsent;
    this.customerServiceBaseUrl =
        customerServiceBaseUrl == null || customerServiceBaseUrl.isBlank()
            ? "http://customer-service:8103"
            : customerServiceBaseUrl;
    this.failureWindow = failureWindow;
    this.maxFailures = maxFailures;
    this.restriction = restriction;
    this.google = google;
    this.kakao =
        Oidc.of(
            kakao,
            "https://kauth.kakao.com/.well-known/jwks.json",
            List.of("https://kauth.kakao.com"));
    this.naver =
        Oidc.of(naver, "https://nid.naver.com/oauth2/jwks", List.of("https://nid.naver.com"));
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

  public boolean requireConsent() {
    return requireConsent;
  }

  public String customerServiceBaseUrl() {
    return customerServiceBaseUrl;
  }

  public Duration failureWindow() {
    return failureWindow;
  }

  public int maxFailures() {
    return maxFailures;
  }

  public Duration restriction() {
    return restriction;
  }

  public Google google() {
    return google;
  }

  public Oidc kakao() {
    return kakao;
  }

  public Oidc naver() {
    return naver;
  }

  /**
   * 카카오·네이버 OpenID Connect(ADR-0071). 공개키 주소·발급자는 제공자 고정값이라 비면 그 값을 쓴다(각 제공자의
   * .well-known/openid-configuration, 2026-11-19 확인). 허용 클라이언트 ID 는 비면 그 제공자 로그인 CONFIG_REQUIRED.
   *
   * @param clientIds 허용할 ID 토큰 aud
   * @param jwkSetUri 제공자 공개키
   * @param issuers 허용 iss
   */
  public record Oidc(
      @Nullable List<String> clientIds,
      @Nullable String jwkSetUri,
      @Nullable List<String> issuers) {

    static Oidc of(@Nullable Oidc o, String defaultJwks, List<String> defaultIssuers) {
      List<String> ids =
          o == null || o.clientIds() == null
              ? List.of()
              : o.clientIds().stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
      return new Oidc(
          ids,
          o == null || o.jwkSetUri() == null || o.jwkSetUri().isBlank()
              ? defaultJwks
              : o.jwkSetUri(),
          o == null || o.issuers() == null || o.issuers().isEmpty()
              ? defaultIssuers
              : List.copyOf(o.issuers()));
    }

    @Override
    public List<String> clientIds() {
      return clientIds == null ? List.of() : clientIds;
    }

    @Override
    public List<String> issuers() {
      return issuers == null ? List.of() : issuers;
    }
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
