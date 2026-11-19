package com.pmart.storefront.application;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorefrontProperties.class)
@org.springframework.scheduling.annotation.EnableScheduling
class StorefrontConfig {

  /** UTC(ADR-0010). DB(마이크로초)와 응답 값이 같도록 마이크로초로 자른다. */
  @Bean
  Clock clock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000));
  }
}
