package com.pmart.payment.application;

import com.pmart.payment.infrastructure.toss.TestAutoTossClient;
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

  /**
   * 토스 클라이언트. {@code pmart.payment.test-auto.enabled=true}(LAN 시험 전용, 2026-11-19 MFC 시뮬레이터 R-21)면
   * {@code TEST_AUTO_<orderId>} 키만 토스 없이 답하는 {@link TestAutoTossClient} — 운영 설정에는 없다.
   */
  @Bean
  TossClient tossClient(
      TossProperties props,
      ObjectMapper mapper,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value(
              "${pmart.payment.test-auto.enabled:false}")
          boolean testAuto) {
    return testAuto ? new TestAutoTossClient(props, mapper, clock) : new TossClient(props, mapper);
  }
}
