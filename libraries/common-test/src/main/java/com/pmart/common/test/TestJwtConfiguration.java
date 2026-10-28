package com.pmart.common.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * 통합 시험에서 서비스의 JwtDecoder 를 {@link TestJwt} 키로 바꾼다. {@code @Import(TestJwtConfiguration.class)} 로
 * 쓴다. 이때도 서비스 설정의 {@code jwk-set-uri} 는 있어야 한다(ServiceBootIT 처럼 아무 주소).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtConfiguration {

  @Bean
  @Primary
  JwtDecoder testJwtDecoder() {
    return TestJwt.decoder();
  }
}
