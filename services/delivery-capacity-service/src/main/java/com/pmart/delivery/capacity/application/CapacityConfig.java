package com.pmart.delivery.capacity.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 정책값 바인딩과 점유 만료 정리({@link HoldExpirySweeper}) 스케줄링. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CapacityProperties.class)
@EnableScheduling
class CapacityConfig {

  /** UTC(ADR-0010). 영업일·시간대 판정만 Asia/Seoul. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
