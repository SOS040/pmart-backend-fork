package com.pmart.product.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ProductConfig {

  /** UTC 저장(ADR-0010). 시험은 고정 시계로 바꾼다. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
