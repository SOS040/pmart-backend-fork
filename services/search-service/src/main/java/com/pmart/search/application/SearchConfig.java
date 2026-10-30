package com.pmart.search.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
class SearchConfig {

  /** UTC(ADR-0010). */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
