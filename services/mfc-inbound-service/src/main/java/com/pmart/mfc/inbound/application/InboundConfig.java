package com.pmart.mfc.inbound.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 초기 보유 반입 전기 주기 작업({@link InitialStockPoster})을 위해 스케줄링을 켠다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(InboundProperties.class)
class InboundConfig {

  /** UTC 저장(ADR-0010). */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
