package com.pmart.audit.application;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 열람 기록 파기 일정(PiiAccessLogPurger, 2026-11-15)과 시계. */
@Configuration
@EnableScheduling
class AuditConfig {

  @Bean
  @ConditionalOnMissingBean
  Clock clock() {
    return Clock.systemUTC();
  }
}
