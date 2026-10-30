package com.pmart.delivery.capacity.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CapacityProperties.class)
class CapacityConfig {

  /** UTC(ADR-0010). 영업일·시간대 판정만 Asia/Seoul. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
