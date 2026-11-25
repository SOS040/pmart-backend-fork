package com.pmart.auth.infrastructure;

import com.pmart.auth.application.CustomerProperties;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
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

/**
 * 소셜 제공자 OpenID Connect ID 토큰 검증(Google·카카오·네이버 — ADR-0055·ADR-0071). 서명은 제공자 공개키(JWKS), iss·aud(허용
 * 클라이언트 ID·앱 키)·만료를 본다. 제공자 비밀값은 쓰지 않는다(ID 토큰 검증에는 필요 없음). 2026-11-19 GoogleIdTokenVerifier 에서 이름을
 * 바꿨다.
 *
 * <ul>
 *   <li>{@link Rejected}: 토큰이 명확히 무효(서명·aud·iss·만료) — 실패 횟수에 넣는다.
 *   <li>{@link Unavailable}: 공개키를 못 받는 등 제공자 쪽 장애 — 실패 횟수에서 뺀다(Commerce 4.9).
 * </ul>
 */
public class IdTokenVerifier {

  /**
   * 검증된 제공자 계정과 Google 이 준 프로필(ADR-0024). auth 는 subject 만 저장하고, 프로필은 CustomerAuthenticated 로
   * customer-service(고객 정보 정본)에 넘긴다. 프로필 값은 앱이 요청한 scope 에 따라 없을 수 있다. 개인정보 — 로그 금지.
   */
  public record Identity(
      String subject,
      @Nullable String email,
      @Nullable Boolean emailVerified,
      @Nullable String name,
      @Nullable String pictureUrl,
      @Nullable String nickname) {

    public Identity(
        String subject,
        @Nullable String email,
        @Nullable Boolean emailVerified,
        @Nullable String name,
        @Nullable String pictureUrl) {
      this(subject, email, emailVerified, name, pictureUrl, null);
    }
  }

  public static class Rejected extends RuntimeException {
    public Rejected(String message) {
      super(message);
    }
  }

  public static class Unavailable extends RuntimeException {
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final JwtDecoder decoder;

  public IdTokenVerifier(JwtDecoder decoder) {
    this.decoder = decoder;
  }

  /** 운영: Google JWKS 를 쓰는 검증기. */
  public static IdTokenVerifier forGoogle(CustomerProperties.Google google) {
    return forProvider(google.jwkSetUri(), google.clientIds(), google.issuers());
  }

  /** 운영: 제공자 JWKS 를 쓰는 검증기(카카오·네이버 — ADR-0071). */
  public static IdTokenVerifier forProvider(
      String jwkSetUri, List<String> clientIds, List<String> issuers) {
    NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    d.setJwtValidator(validator(clientIds, issuers));
    return new IdTokenVerifier(d);
  }

  /** 서명 이외의 검증(운영·시험 공통). */
  public static OAuth2TokenValidator<Jwt> validator(CustomerProperties.Google google) {
    return validator(google.clientIds(), google.issuers());
  }

  /** 서명 이외의 검증 — 만료·iss(허용 목록)·aud(허용 클라이언트 ID 중 하나). */
  public static OAuth2TokenValidator<Jwt> validator(List<String> clientIds, List<String> issuers) {
    List<OAuth2TokenValidator<Jwt>> v = new ArrayList<>();
    v.add(new JwtTimestampValidator());
    v.add(
        jwt ->
            issuers.contains(jwt.getClaimAsString("iss"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "iss", null)));
    v.add(
        jwt ->
            jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(clientIds::contains)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "aud", null)));
    return new DelegatingOAuth2TokenValidator<>(v);
  }

  public Identity verify(String idToken) {
    Jwt jwt;
    try {
      jwt = decoder.decode(idToken);
    } catch (BadJwtException e) {
      // 서명·형식·iss·aud·만료 등 토큰 자체의 무효(JwtValidationException 포함)
      throw new Rejected(e.getMessage());
    } catch (JwtException e) {
      // 공개키 조회 실패 등
      throw new Unavailable("제공자 공개키를 확인할 수 없습니다.", e);
    }
    if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
      throw new Rejected("sub 없음");
    }
    return new Identity(
        jwt.getSubject(),
        blankToNull(jwt.getClaimAsString("email")),
        jwt.hasClaim("email_verified") ? jwt.getClaimAsBoolean("email_verified") : null,
        // 이름은 실명 클레임 name 만(2026-11-25 사용자 "이름은 무조건 입력하게 하는데" — 고객앱이 입력받는다). 카카오 ID 토큰의
        // nickname 은 닉네임 칸으로. 네이버 ID 토큰은 프로필 클레임이 없다
        blankToNull(jwt.getClaimAsString("name")),
        blankToNull(jwt.getClaimAsString("picture")),
        blankToNull(jwt.getClaimAsString("nickname")));
  }

  private static @Nullable String blankToNull(@Nullable String v) {
    return v == null || v.isBlank() ? null : v;
  }
}
