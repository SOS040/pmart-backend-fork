package com.pmart.cdc.inventory.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class InventoryConfig {

  /** UTC 저장(ADR-0010). */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
