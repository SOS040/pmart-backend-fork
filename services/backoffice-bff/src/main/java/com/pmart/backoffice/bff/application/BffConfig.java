package com.pmart.backoffice.bff.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class BffConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
