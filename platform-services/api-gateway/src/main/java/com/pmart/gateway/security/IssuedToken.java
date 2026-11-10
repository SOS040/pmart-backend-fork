package com.pmart.gateway.security;

import com.pmart.common.jwt.IssuerPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import reactor.core.publisher.Mono;

/**
 * 발급자 표(ADR-0054)로 인증된 토큰 — 사용자 종류는 토큰 문자열이 아니라 발급자 규칙에서 정한 값. 게이트웨이의 SERVICE·GUEST 제한은 이 값을 본다.
 */
public class IssuedToken extends JwtAuthenticationToken {

  private final String userType;
  private final String issuerId;

  IssuedToken(
      Jwt jwt,
      Collection<? extends GrantedAuthority> authorities,
      String subject,
      String userType,
      String issuerId) {
    super(jwt, authorities, subject);
    this.userType = userType;
    this.issuerId = issuerId;
  }

  public String userType() {
    return userType;
  }

  public String issuerId() {
    return issuerId;
  }

  /** 인증 결과의 사용자 종류: 발급자 표로 인증됐으면 그 값, 아니면(표 없음) {@code typ} 클레임. */
  static @Nullable String userTypeOf(Authentication authentication) {
    if (authentication instanceof IssuedToken t) {
      return t.userType();
    }
    return authentication.getPrincipal() instanceof Jwt jwt
        ? jwt.getClaimAsString(IssuerPolicy.CLAIM_TYPE)
        : null;
  }

  static Converter<Jwt, AbstractAuthenticationToken> converter(IssuerPolicy policy) {
    return jwt -> {
      IssuerPolicy.Claims c = policy.claims(jwt);
      Collection<GrantedAuthority> authorities = new ArrayList<>();
      c.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
      authorities.add(new SimpleGrantedAuthority("TYPE_" + c.userType()));
      return new IssuedToken(jwt, authorities, c.subject(), c.userType(), policy.id());
    };
  }

  /**
   * 발급자 표 → {@code iss} 로 고르는 리액티브 인증. 표에 없는 발급자는 401.
   *
   * @param defaultDecoder {@code jwk-set-uri} 를 비운 발급자가 쓸 앱 디코더(Boot {@code jwk-set-uri})
   */
  static JwtIssuerReactiveAuthenticationManagerResolver resolver(
      Map<String, IssuerPolicy> table, @Nullable ReactiveJwtDecoder defaultDecoder) {
    Map<String, ReactiveAuthenticationManager> managers = new LinkedHashMap<>();
    table.forEach(
        (iss, policy) -> {
          JwtReactiveAuthenticationManager m =
              new JwtReactiveAuthenticationManager(decoder(policy, defaultDecoder));
          m.setJwtAuthenticationConverter(
              new ReactiveJwtAuthenticationConverterAdapter(converter(policy)));
          managers.put(iss, m);
        });
    return new JwtIssuerReactiveAuthenticationManagerResolver(
        iss -> Mono.justOrEmpty(managers.get(iss)));
  }

  static ReactiveJwtDecoder decoder(
      IssuerPolicy policy, @Nullable ReactiveJwtDecoder defaultDecoder) {
    OAuth2TokenValidator<Jwt> validator = policy.validator();
    String jwkSetUri = policy.jwkSetUri();
    if (jwkSetUri != null) {
      NimbusReactiveJwtDecoder d = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
      d.setJwtValidator(validator);
      return d;
    }
    if (defaultDecoder == null) {
      throw new IllegalStateException(
          "발급자 " + policy.id() + " 는 jwk-set-uri 가 없고 게이트웨이 기본 디코더도 없습니다.");
    }
    return token ->
        defaultDecoder
            .decode(token)
            .flatMap(
                jwt -> {
                  OAuth2TokenValidatorResult r = validator.validate(jwt);
                  return r.hasErrors()
                      ? Mono.error(
                          new JwtValidationException(
                              r.getErrors().iterator().next().getDescription(), r.getErrors()))
                      : Mono.just(jwt);
                });
  }
}
