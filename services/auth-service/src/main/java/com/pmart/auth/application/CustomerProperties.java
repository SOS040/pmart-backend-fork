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
 * @param naver 네이버 ID 토큰 검증(ADR-0071) — client-ids = 네이버 애플리케이션 Client ID(ID 토큰 aud). 인가 코드 서버 교환
 *     (ADR-0078) 설정 client-secret·callback-url·token-base-url·token-timeout 도 여기({@link Naver})
 * @param requireIdentity 휴대폰 확인 단계(AUTH_IDENTITY, ADR-0074 2026-11-20) — 켜면 가입과 확인 번호가 없는 기존 회원은 필수
 *     동의 뒤 휴대폰 확인을 거친다(번호 정본 customer-service). 고객 앱이 새 단계를 반영한 뒤 켠다(기본 false)
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
  private final NaverExchange naverExchange;
  private final boolean requireIdentity;

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
      @Nullable Naver naver,
      boolean requireIdentity) {
    this.requireIdentity = requireIdentity;
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
        Oidc.of(
            naver == null ? null : naver.oidc(),
            "https://nid.naver.com/oauth2/jwks",
            List.of("https://nid.naver.com"));
    this.naverExchange = NaverExchange.of(this.naver.clientIds(), naver);
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

  public boolean requireIdentity() {
    return requireIdentity;
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

  /** 네이버 인가 코드 서버 교환 설정(ADR-0078). */
  public NaverExchange naverExchange() {
    return naverExchange;
  }

  /**
   * {@code pmart.auth.customer.naver.*} 원본. ID 토큰 검증 값({@link #oidc()})과 인가 코드 서버 교환 값(ADR-0078,
   * 2026-11-20 BR-69)을 함께 받는다.
   *
   * @param clientIds 허용할 ID 토큰 aud(네이버 Client ID). 교환에는 첫 값을 client_id 로 쓴다
   * @param jwkSetUri 제공자 공개키
   * @param issuers 허용 iss
   * @param clientSecret 네이버 Client Secret — pmart-config {@code auth-service-<프로필>.yml} 의 {@code
   *     {cipher}}. 로그 금지
   * @param callbackUrl 앱이 인가 요청에 쓴 redirect_uri 로 허용하는 값(이 서비스의 네이버 콜백 중계 주소, 고객용)
   * @param tokenBaseUrl 네이버 토큰 엔드포인트 기준 주소(기본 {@code https://nid.naver.com}, 경로 {@code
   *     /oauth2/token})
   * @param tokenTimeout 토큰 요청 응답 대기(기본 5초) — 클라이언트를 만들 때 한 번 읽는다(바꾸면 재시작)
   */
  public record Naver(
      @Nullable List<String> clientIds,
      @Nullable String jwkSetUri,
      @Nullable List<String> issuers,
      @Nullable String clientSecret,
      @Nullable String callbackUrl,
      @Nullable String tokenBaseUrl,
      @Nullable Duration tokenTimeout) {

    Oidc oidc() {
      return new Oidc(clientIds, jwkSetUri, issuers);
    }

    @Override
    public String toString() {
      return "Naver[clientIds="
          + clientIds
          + ", callbackUrl="
          + callbackUrl
          + ", clientSecret=***]";
    }
  }

  /**
   * 네이버 인가 코드 서버 교환(ADR-0078). 비어 있는 값은 교환 요청 때 422 CONFIG_REQUIRED.
   *
   * @param clientId 허용 client-ids 의 첫 값
   * @param clientSecret Client Secret(로그 금지 — toString 에서 가린다)
   * @param callbackUrl 허용 redirect_uri
   * @param tokenBaseUrl 토큰 엔드포인트 기준 주소(끝 / 없음)
   * @param tokenTimeout 응답 대기
   */
  public record NaverExchange(
      @Nullable String clientId,
      @Nullable String clientSecret,
      @Nullable String callbackUrl,
      String tokenBaseUrl,
      Duration tokenTimeout) {

    static NaverExchange of(List<String> clientIds, @Nullable Naver n) {
      return new NaverExchange(
          clientIds.isEmpty() ? null : clientIds.get(0),
          n == null ? null : blankToNull(n.clientSecret()),
          n == null ? null : blankToNull(n.callbackUrl()),
          n == null || blankToNull(n.tokenBaseUrl()) == null
              ? "https://nid.naver.com"
              : n.tokenBaseUrl().strip().replaceAll("/+$", ""),
          n == null || n.tokenTimeout() == null || n.tokenTimeout().isZero()
              ? Duration.ofSeconds(5)
              : n.tokenTimeout());
    }

    private static @Nullable String blankToNull(@Nullable String v) {
      return v == null || v.isBlank() ? null : v.strip();
    }

    @Override
    public String toString() {
      return "NaverExchange[clientId="
          + clientId
          + ", callbackUrl="
          + callbackUrl
          + ", tokenBaseUrl="
          + tokenBaseUrl
          + ", clientSecret="
          + (clientSecret == null ? "null" : "***")
          + "]";
    }
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
