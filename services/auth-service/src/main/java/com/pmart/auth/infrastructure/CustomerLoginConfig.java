package com.pmart.auth.infrastructure;

import com.pmart.auth.application.CustomerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 고객 소셜 로그인 제공자 검증기. 시험은 같은 타입 빈을 넣어 바꾼다. */
@Configuration(proxyBeanMethods = false)
class CustomerLoginConfig {

  /** 제공자별 ID 토큰 검증기(GOOGLE·KAKAO·NAVER — ADR-0055·ADR-0071). */
  @Bean
  @ConditionalOnMissingBean
  IdTokenVerifiers idTokenVerifiers(CustomerProperties props) {
    return IdTokenVerifiers.forProperties(props);
  }
}
