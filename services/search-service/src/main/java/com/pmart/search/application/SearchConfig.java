package com.pmart.search.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SearchProperties.class, SemanticProperties.class})
@EnableScheduling
class SearchConfig {

  /** UTC(ADR-0010). */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
