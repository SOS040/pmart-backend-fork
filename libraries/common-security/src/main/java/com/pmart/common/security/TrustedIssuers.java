package com.pmart.common.security;

import com.pmart.common.jwt.IssuerPolicy;
import com.pmart.common.jwt.IssuerSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;

/**
 * 발급자 표 → 서블릿 인증(ADR-0054). 토큰의 {@code iss} 로 발급자를 고르고(표에 없으면 401), 그 발급자 키로 서명 확인 뒤 {@link
 * IssuerPolicy#validator()} 를 본다. 게이트웨이(리액티브)는 같은 {@link IssuerPolicy} 로 자기 쪽을 만든다.
 */
public final class TrustedIssuers {

  private final Map<String, IssuerPolicy> byIssuer;

  public TrustedIssuers(List<IssuerSettings> issuers) {
    this.byIssuer = IssuerPolicy.table(issuers);
  }

  public Map<String, IssuerPolicy> policies() {
    return byIssuer;
  }

  /**
   * @param defaultDecoder 표에서 {@code jwk-set-uri} 를 비운 발급자가 쓸 디코더(앱의 JwtDecoder). 없으면 그런 발급자는 설정
   *     오류.
   */
  public JwtIssuerAuthenticationManagerResolver resolver(@Nullable JwtDecoder defaultDecoder) {
    Map<String, AuthenticationManager> managers = new LinkedHashMap<>();
    byIssuer.forEach(
        (iss, policy) -> {
          JwtDecoder decoder = decoder(policy, defaultDecoder, NimbusJwtDecoder::withJwkSetUri);
          JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
          provider.setJwtAuthenticationConverter(PmartAuthenticationToken.converter(policy));
          managers.put(iss, provider::authenticate);
        });
    return new JwtIssuerAuthenticationManagerResolver(managers::get);
  }

  static JwtDecoder decoder(
      IssuerPolicy policy,
      @Nullable JwtDecoder defaultDecoder,
      Function<String, NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder> builder) {
    OAuth2TokenValidator<Jwt> validator = policy.validator();
    if (policy.jwkSetUri() != null) {
      NimbusJwtDecoder d = builder.apply(policy.jwkSetUri()).build();
      d.setJwtValidator(validator);
      return d;
    }
    if (defaultDecoder == null) {
      throw new IllegalStateException(
          "발급자 " + policy.id() + " 는 jwk-set-uri 가 없고 앱의 JwtDecoder 도 없습니다.");
    }
    // 앱 디코더(Boot jwk-set-uri, 서비스 자체 키, 시험 키)로 서명을 확인하고 이 발급자 규칙을 더 본다.
    return token -> {
      Jwt jwt = defaultDecoder.decode(token);
      OAuth2TokenValidatorResult r = validator.validate(jwt);
      if (r.hasErrors()) {
        throw new JwtValidationException(
            r.getErrors().iterator().next().getDescription(), r.getErrors());
      }
      return jwt;
    };
  }
}
