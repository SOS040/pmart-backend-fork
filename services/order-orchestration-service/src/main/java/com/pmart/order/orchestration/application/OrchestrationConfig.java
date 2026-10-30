package com.pmart.order.orchestration.application;

import java.time.Clock;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OrchestrationProperties.class)
class OrchestrationConfig {

  /** UTC(ADR-0010). 작업 창(정시)만 Asia/Seoul. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** 사가 단계 실행(응답 뒤 비동기). 가상 스레드 — 단계는 대부분 하위 HTTP 대기다. */
  @Bean(destroyMethod = "close")
  java.util.concurrent.ExecutorService sagaExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
