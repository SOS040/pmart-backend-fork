package com.pmart.approval.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApprovalProperties.class)
class ApprovalConfig {

  /** UTC 저장(ADR-0010). 시험은 고정 시계로 바꾼다. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
