package com.pmart.auth.infrastructure.keys;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 서명키·JWT 인코더·디코더.
 *
 * <p>auth-service 자신은 JWKS 를 HTTP 로 다시 받지 않고 같은 키 묶음으로 검증한다(다른 서비스는 {@code jwk-set-uri}). 이 {@link
 * JwtDecoder} 가 있으면 Boot 자동구성은 물러나고 common-security 체인이 이것을 쓴다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class KeyConfig {

  @Bean
  SigningKeyRing signingKeyRing(AuthProperties properties) {
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
