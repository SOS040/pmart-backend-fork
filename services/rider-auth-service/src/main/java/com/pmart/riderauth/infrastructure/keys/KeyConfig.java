package com.pmart.riderauth.infrastructure.keys;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.pmart.riderauth.application.RiderAuthProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 라이더 토큰 서명키·JWT 인코더·디코더. 이 {@link JwtDecoder} 는 앱의 기본 디코더다 — 발급자 표가 없으면(시험) 이것으로 자기 토큰을 검증하고, 표가
 * 있으면 jwk-set-uri 를 비운 줄이 이것을 쓴다. 표의 rider 줄은 이 서비스의 JWKS 주소를 가리킨다(자기 자신도 같은 키).
 */
@Configuration(proxyBeanMethods = false)
public class KeyConfig {

  @Bean
  SigningKeyRing signingKeyRing(RiderAuthProperties properties) {
    return SigningKeyRing.from(properties.signing());
  }

  @Bean
  public JwtEncoder jwtEncoder(SigningKeyRing keys) {
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(keys.privateKeys()));
  }

  @Bean
  public JwtDecoder jwtDecoder(SigningKeyRing keys) {
    JWKSource<SecurityContext> source = new ImmutableJWKSet<>(keys.privateKeys().toPublicJWKSet());
    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, source));
    return new NimbusJwtDecoder(processor);
  }

  @Bean
  @ConditionalOnMissingBean
  Clock clock() {
    return Clock.systemUTC();
  }
}
