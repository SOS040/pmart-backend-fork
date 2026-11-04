package com.pmart.order.orchestration.application;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
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

  /**
   * 사가 단계 실행(응답 뒤 비동기). 가상 스레드 — 단계는 대부분 하위 HTTP 대기다. 호출 스레드의 관측(trace)·MDC 를 넘긴다 (작업 A,
   * trace-correlation-plan.md) — 안 넘기면 참여자 호출마다 새 trace·새 correlationId 가 된다.
   */
  @Bean(destroyMethod = "close")
  java.util.concurrent.ExecutorService sagaExecutor() {
    return ContextExecutorService.wrap(
        Executors.newVirtualThreadPerTaskExecutor(),
        () -> ContextSnapshotFactory.builder().build().captureAll());
  }
}
