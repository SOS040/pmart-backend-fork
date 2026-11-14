package com.pmart.employeeauth.infrastructure;

import com.pmart.employeeauth.application.EmployeeAuthProperties;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Keycloak(직원 신원 확인, ADR-0056) 서버 쪽 호출 — 인가 코드 교환(client_secret_basic + PKCE), ID 토큰
 * 검증(서명·iss·aud·azp·만료·nonce), Keycloak 세션 끝내기, (LAN 시험) access token 검증. 토큰 값·비밀은 로그에 남기지 않는다.
 *
 * <ul>
 *   <li>{@link Rejected}: Keycloak 이 거절했거나 토큰이 무효 — 사용자에게 IDP_REJECTED.
 *   <li>{@link Unavailable}: Keycloak 에 닿지 않음·공개키를 못 받음 — IDP_UNAVAILABLE.
 * </ul>
 */
public class KeycloakClient {

  private static final Logger log = LoggerFactory.getLogger(KeycloakClient.class);

  public static final String CLAIM_OPERATOR_ID = "pmart_operator_id";

  /**
   * 검증된 Keycloak 사용자. operatorId 는 사용자 속성(첫 로그인 연결용). sid = Keycloak 세션 ID(ID 토큰 {@code sid} — 같은
   * 브라우저 로그인을 묶는 값, ADR-0061).
   */
  public record Identity(String subject, @Nullable String operatorId, @Nullable String sid) {}

  /** 검증된 백채널 로그아웃(OIDC Back-Channel Logout 1.0). sid·sub 중 하나 이상. */
  public record LogoutClaims(@Nullable String subject, @Nullable String sid) {}

  /** logout_token {@code events} 의 키(규약 고정값). */
  public static final String BACKCHANNEL_LOGOUT_EVENT =
      "http://schemas.openid.net/event/backchannel-logout";

  /** 코드 교환 결과(이 서비스 안에서만 쓰고 버린다). */
  public record Tokens(String idToken, @Nullable String refreshToken) {}

  public static class Rejected extends RuntimeException {
    public Rejected(String message) {
      super(message);
    }
  }

  public static class Unavailable extends RuntimeException {
    public Unavailable(String message, @Nullable Throwable cause) {
      super(message, cause);
    }
  }

  private final EmployeeAuthProperties.Keycloak kc;
  private final List<String> devClientIds;
  private final RestClient http;
  private volatile @Nullable JwtDecoder idTokens;
  private volatile @Nullable JwtDecoder devAccessTokens;
  private volatile @Nullable JwtDecoder logoutTokens;

  public KeycloakClient(EmployeeAuthProperties props, RestClient.Builder builder) {
    this.kc = props.keycloak();
    this.devClientIds = Objects.requireNonNull(props.devTokenExchange().clientIds());
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Objects.requireNonNull(kc.timeout()))
                .build());
    factory.setReadTimeout(Objects.requireNonNull(kc.timeout()));
    this.http = builder.clone().requestFactory(factory).build();
  }

  /** 시험이 서명 확인만 바꿀 때(그 밖의 검증은 운영 규칙 그대로). */
  public KeycloakClient withDecoders(JwtDecoder idTokens, JwtDecoder devAccessTokens) {
    this.idTokens = idTokens;
    this.devAccessTokens = devAccessTokens;
    return this;
  }

  /** 시험이 백채널 로그아웃 토큰의 서명 확인만 바꿀 때. */
  public KeycloakClient withLogoutDecoder(JwtDecoder logoutTokens) {
    this.logoutTokens = logoutTokens;
    return this;
  }

  // 공개키 주소는 Keycloak 설정이 있을 때만 쓰므로 처음 쓸 때 만든다(설정 없는 기동·시험에서 상대 주소로 실패하지 않게)
  private JwtDecoder idTokens() {
    JwtDecoder d = idTokens;
    if (d == null) {
      NimbusJwtDecoder n =
          NimbusJwtDecoder.withJwkSetUri(Objects.requireNonNull(kc.jwkSetUri())).build();
      n.setJwtValidator(idTokenValidator(kc));
      idTokens = d = n;
    }
    return d;
  }

  private JwtDecoder logoutTokens() {
    JwtDecoder d = logoutTokens;
    if (d == null) {
      NimbusJwtDecoder n =
          NimbusJwtDecoder.withJwkSetUri(Objects.requireNonNull(kc.jwkSetUri())).build();
      n.setJwtValidator(logoutTokenValidator(kc));
      logoutTokens = d = n;
    }
    return d;
  }

  private JwtDecoder devAccessTokens() {
    JwtDecoder d = devAccessTokens;
    if (d == null) {
      NimbusJwtDecoder n =
          NimbusJwtDecoder.withJwkSetUri(Objects.requireNonNull(kc.jwkSetUri())).build();
      n.setJwtValidator(devTokenValidator(kc, devClientIds));
      devAccessTokens = d = n;
    }
    return d;
  }

  /** 인가 코드 → 토큰(서버끼리). */
  @SuppressWarnings("unchecked")
  public Tokens exchange(String code, String codeVerifier) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("code", code);
    form.add("redirect_uri", kc.redirectUri());
    form.add("code_verifier", codeVerifier);
    Map<String, Object> body;
    try {
      body =
          http.post()
              .uri(Objects.requireNonNull(kc.tokenUri()))
              .headers(
                  h ->
                      h.setBasicAuth(
                          Objects.requireNonNull(kc.clientId()),
                          Objects.requireNonNull(kc.clientSecret())))
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(Map.class);
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().is4xxClientError()) {
        // 코드 만료·재사용·verifier 불일치 등. 응답 본문은 로그에 싣지 않는다(코드가 섞일 수 있음)
        throw new Rejected("Keycloak 코드 교환 거절 " + e.getStatusCode().value());
      }
      throw new Unavailable("Keycloak 코드 교환 실패 " + e.getStatusCode().value(), e);
    } catch (RestClientException e) {
      throw new Unavailable("Keycloak 에 닿지 않음", e);
    }
    Object idToken = body == null ? null : body.get("id_token");
    if (!(idToken instanceof String s) || s.isBlank()) {
      throw new Rejected("ID 토큰 없음(scope openid 확인)");
    }
    Object refresh = body.get("refresh_token");
    return new Tokens(s, refresh instanceof String r ? r : null);
  }

  /** ID 토큰 검증 + nonce 확인. */
  public Identity verifyIdToken(String idToken, String nonce) {
    Jwt jwt = decode(idTokens(), idToken);
    if (!nonce.equals(jwt.getClaimAsString("nonce"))) {
      throw new Rejected("nonce 불일치");
    }
    return identity(jwt);
  }

  /**
   * 백채널 로그아웃 토큰 검증(ADR-0061) — 서명(Keycloak JWKS)·만료·iss·aud(이 클라이언트)·events·nonce 없음·sid 또는 sub.
   *
   * @throws Rejected 무효(규약상 400)
   * @throws Unavailable 공개키를 못 받음
   */
  public LogoutClaims verifyLogoutToken(String logoutToken) {
    Jwt jwt = decode(logoutTokens(), logoutToken);
    String sid = jwt.getClaimAsString("sid");
    String sub = jwt.getSubject();
    return new LogoutClaims(
        sub == null || sub.isBlank() ? null : sub, sid == null || sid.isBlank() ? null : sid);
  }

  /** (LAN 시험) pmart-dev-cli 등이 직접 로그인으로 받은 Keycloak access token 검증. */
  public Identity verifyDevAccessToken(String accessToken) {
    return identity(decode(devAccessTokens(), accessToken));
  }

  /**
   * Keycloak 세션을 바로 끝낸다(ADR-0056 결정 14 ① — 직원 세션은 PMart 세션이고, 공용 PDA 다음 작업자가 앞사람 Keycloak 세션으로 들어가지
   * 않게). 실패해도 로그인은 계속한다(로그인 시작에 prompt=login 을 붙이므로 다음 로그인은 어쨌든 비밀번호를 묻는다).
   */
  public void endSession(@Nullable String refreshToken) {
    if (refreshToken == null) {
      return;
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("refresh_token", refreshToken);
    try {
      http.post()
          .uri(Objects.requireNonNull(kc.logoutUri()))
          .headers(
              h ->
                  h.setBasicAuth(
                      Objects.requireNonNull(kc.clientId()),
                      Objects.requireNonNull(kc.clientSecret())))
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      log.warn("Keycloak 세션 끝내기 실패(로그인은 계속): {}", e.getClass().getSimpleName());
    }
  }

  private static Jwt decode(JwtDecoder decoder, String token) {
    try {
      return decoder.decode(token);
    } catch (BadJwtException e) {
      throw new Rejected(e.getMessage());
    } catch (JwtException e) {
      throw new Unavailable("Keycloak 공개키를 확인할 수 없음", e);
    }
  }

  private static Identity identity(Jwt jwt) {
    if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
      throw new Rejected("sub 없음");
    }
    String op = jwt.getClaimAsString(CLAIM_OPERATOR_ID);
    String sid = jwt.getClaimAsString("sid");
    return new Identity(
        jwt.getSubject(),
        op == null || op.isBlank() ? null : op,
        sid == null || sid.isBlank() ? null : sid);
  }

  /** ID 토큰: 만료·iss(정확히)·aud 에 이 클라이언트·azp 가 있으면 이 클라이언트. */
  public static OAuth2TokenValidator<Jwt> idTokenValidator(EmployeeAuthProperties.Keycloak kc) {
    List<OAuth2TokenValidator<Jwt>> v = new ArrayList<>();
    v.add(new JwtTimestampValidator());
    v.add(issuer(kc));
    v.add(
        jwt ->
            jwt.getAudience() != null && jwt.getAudience().contains(kc.clientId())
                ? OAuth2TokenValidatorResult.success()
                : fail("aud"));
    v.add(
        jwt -> {
          String azp = jwt.getClaimAsString("azp");
          return azp == null || azp.equals(kc.clientId())
              ? OAuth2TokenValidatorResult.success()
              : fail("azp");
        });
    return new DelegatingOAuth2TokenValidator<>(v);
  }

  /** 백채널 로그아웃 토큰: 만료·iss·aud 에 이 클라이언트·events 에 back-channel logout·nonce 없음·sid 또는 sub. */
  public static OAuth2TokenValidator<Jwt> logoutTokenValidator(EmployeeAuthProperties.Keycloak kc) {
    List<OAuth2TokenValidator<Jwt>> v = new ArrayList<>();
    v.add(new JwtTimestampValidator());
    v.add(issuer(kc));
    v.add(
        jwt ->
            jwt.getAudience() != null && jwt.getAudience().contains(kc.clientId())
                ? OAuth2TokenValidatorResult.success()
                : fail("aud"));
    v.add(
        jwt -> {
          Object events = jwt.getClaims().get("events");
          return events instanceof Map<?, ?> m && m.containsKey(BACKCHANNEL_LOGOUT_EVENT)
              ? OAuth2TokenValidatorResult.success()
              : fail("events");
        });
    v.add(
        jwt ->
            jwt.getClaims().containsKey("nonce")
                ? fail("nonce")
                : OAuth2TokenValidatorResult.success());
    v.add(
        jwt -> {
          String sid = jwt.getClaimAsString("sid");
          String sub = jwt.getSubject();
          return (sid != null && !sid.isBlank()) || (sub != null && !sub.isBlank())
              ? OAuth2TokenValidatorResult.success()
              : fail("sid/sub");
        });
    return new DelegatingOAuth2TokenValidator<>(v);
  }

  /** 시험 교환용 access token: 만료·iss·azp 가 허용 개발 클라이언트. */
  public static OAuth2TokenValidator<Jwt> devTokenValidator(
      EmployeeAuthProperties.Keycloak kc, List<String> clientIds) {
    List<OAuth2TokenValidator<Jwt>> v = new ArrayList<>();
    v.add(new JwtTimestampValidator());
    v.add(issuer(kc));
    v.add(
        jwt ->
            clientIds.contains(jwt.getClaimAsString("azp"))
                ? OAuth2TokenValidatorResult.success()
                : fail("azp"));
    return new DelegatingOAuth2TokenValidator<>(v);
  }

  private static OAuth2TokenValidator<Jwt> issuer(EmployeeAuthProperties.Keycloak kc) {
    return jwt ->
        kc.issuer() != null && kc.issuer().equals(jwt.getClaimAsString("iss"))
            ? OAuth2TokenValidatorResult.success()
            : fail("iss");
  }

  private static OAuth2TokenValidatorResult fail(String what) {
    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", what, null));
  }
}
