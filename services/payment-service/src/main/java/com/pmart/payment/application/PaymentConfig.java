package com.pmart.payment.application;

import com.pmart.payment.infrastructure.toss.TossClient;
import com.pmart.payment.infrastructure.toss.TossProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/** 토스 클라이언트·시계·주기 작업(불명 재조회·만료). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(TossProperties.class)
class PaymentConfig {

  /** UTC 저장(ADR-0010). DB(마이크로초)와 응답 값이 같도록 마이크로초로 자른다. */
  @Bean
  Clock clock() {
    return Clock.tick(
        Clock.systemUTC(), java.time.Duration.of(1, java.time.temporal.ChronoUnit.MICROS));
  }

  @Bean
  TossClient tossClient(TossProperties props, ObjectMapper mapper) {
    return new TossClient(props, mapper);
  }
}
