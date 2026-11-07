package com.pmart.mfc.inventory.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 예약 만료 작업({@link HoldExpiry})을 위해 스케줄링을 켜고, 재고 정책 설정을 읽는다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(InventoryProperties.class)
class InventoryConfig {

  /** UTC 저장(ADR-0010). */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
