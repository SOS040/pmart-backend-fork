package com.pmart.claim.application;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ClaimProperties.class)
class ClaimConfig {

  /** UTC(ADR-0010). DB(마이크로초)와 응답 값이 같도록 마이크로초로 자른다. */
  @Bean
  Clock clock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000));
  }

  /** 환불 실행(사건 처리 커밋 뒤 비동기). 가상 스레드 — 대부분 payment HTTP 대기다. 호출 스레드의 trace·MDC 를 넘긴다(작업 A). */
  @Bean(destroyMethod = "close")
  ExecutorService refundExecutor() {
    return ContextExecutorService.wrap(
        Executors.newVirtualThreadPerTaskExecutor(),
        () -> ContextSnapshotFactory.builder().build().captureAll());
  }
}
