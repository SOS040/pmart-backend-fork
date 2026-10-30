package com.pmart.auth.infrastructure;

import com.pmart.auth.application.CustomerProperties;
import java.util.ArrayList;
import java.util.List;
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
 * Google ID 토큰 검증(고객 앱 Google 로그인). 서명은 Google 공개키(JWKS), iss·aud(허용 클라이언트 ID)·만료를 본다. 제공자 비밀값은 쓰지
 * 않는다(ID 토큰 검증에는 필요 없음).
 *
 * <ul>
 *   <li>{@link Rejected}: 토큰이 명확히 무효(서명·aud·iss·만료) — 실패 횟수에 넣는다.
 *   <li>{@link Unavailable}: 공개키를 못 받는 등 제공자 쪽 장애 — 실패 횟수에서 뺀다(Commerce 4.9).
 * </ul>
 */
public class GoogleIdTokenVerifier {

  /** 검증된 제공자 계정. */
  public record Identity(String subject) {}

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

  public GoogleIdTokenVerifier(JwtDecoder decoder) {
    this.decoder = decoder;
  }

  /** 운영: Google JWKS 를 쓰는 검증기. */
  public static GoogleIdTokenVerifier forGoogle(CustomerProperties.Google google) {
    NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(google.jwkSetUri()).build();
    d.setJwtValidator(validator(google));
    return new GoogleIdTokenVerifier(d);
  }

  /** 서명 이외의 검증(운영·시험 공통). */
  public static OAuth2TokenValidator<Jwt> validator(CustomerProperties.Google google) {
    List<OAuth2TokenValidator<Jwt>> v = new ArrayList<>();
    v.add(new JwtTimestampValidator());
    v.add(
        jwt ->
            google.issuers().contains(jwt.getClaimAsString("iss"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "iss", null)));
    v.add(
        jwt ->
            jwt.getAudience() != null
                    && jwt.getAudience().stream().anyMatch(google.clientIds()::contains)
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
      throw new Unavailable("Google 공개키를 확인할 수 없습니다.", e);
    }
    if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
      throw new Rejected("sub 없음");
    }
    return new Identity(jwt.getSubject());
  }
}
