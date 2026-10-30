package com.pmart.pricing.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 예약 가격 발효({@link PriceActivator})를 위해 스케줄링을 켠다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class PricingConfig {

  /** UTC 저장(ADR-0010). 시험은 고정 시계로 바꾼다. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
