package com.pmart.mfc.capacity.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 정책값·시계, 만료 Hold 정리({@link HoldExpirySweeper})를 위한 스케줄링. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  CapacityProperties.class,
  PlanProperties.class,
  ReceivingProperties.class
})
@EnableScheduling
class CapacityConfig {

  /** UTC(ADR-0010). 창 정시 판정만 Asia/Seoul. 시험은 시계를 바꾼다. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
