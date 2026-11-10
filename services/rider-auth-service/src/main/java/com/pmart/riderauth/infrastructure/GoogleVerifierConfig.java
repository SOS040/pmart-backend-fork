package com.pmart.riderauth.infrastructure;

import com.pmart.riderauth.application.RiderAuthProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Google ID 토큰 검증기. 시험은 같은 타입 빈을 넣어 바꾼다. */
@Configuration(proxyBeanMethods = false)
class GoogleVerifierConfig {

  @Bean
  @ConditionalOnMissingBean
  GoogleIdTokenVerifier googleIdTokenVerifier(RiderAuthProperties props) {
    return GoogleIdTokenVerifier.forGoogle(props.google());
  }
}
