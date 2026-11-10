package com.pmart.gateway.security;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * 주체 차단 목록(ADR-0054 ⑤). {@code pmart.gateway.subject-blocklist.enabled=false} 면 확인 필터·사건 함수를 두지
 * 않는다(단위 시험 — 그때 {@code spring.cloud.function.definition} 에서도 뺀다).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SubjectBlocklistConfig.Settings.class)
@ConditionalOnProperty(
    prefix = "pmart.gateway.subject-blocklist",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SubjectBlocklistConfig {

  /**
   * {@code pmart.gateway.subject-blocklist.*}.
   *
   * @param maxTokenLifetime 모든 발급자의 access token 중 가장 긴 수명 — 차단 항목을 이만큼 남긴다(기본 2시간: 고객 30분·직원
   *     15분·비회원 1시간보다 길게). 발급자 수명을 늘리면 같이 늘린다
   * @param lookupTimeout 요청마다 Redis 조회 상한(넘으면 통과)
   * @param writeTimeout 사건 반영 때 Redis 쓰기 상한(넘으면 재시도·DLT)
   */
  @ConfigurationProperties(prefix = "pmart.gateway.subject-blocklist")
  public record Settings(
      @Nullable Boolean enabled,
      @Nullable Duration maxTokenLifetime,
      @Nullable Duration lookupTimeout,
      @Nullable Duration writeTimeout) {
    public Settings {
      maxTokenLifetime = maxTokenLifetime == null ? Duration.ofHours(2) : maxTokenLifetime;
      lookupTimeout = lookupTimeout == null ? Duration.ofMillis(200) : lookupTimeout;
      writeTimeout = writeTimeout == null ? Duration.ofSeconds(5) : writeTimeout;
    }
  }

  @Bean
  SubjectBlocklist subjectBlocklist(ReactiveStringRedisTemplate redis) {
    return new SubjectBlocklist(redis);
  }

  @Bean
  SubjectBlockFilter subjectBlockFilter(
      SubjectBlocklist blocklist,
      Settings settings,
      ProblemResponseWriter problems,
      ObjectProvider<MeterRegistry> meters) {
    return new SubjectBlockFilter(
        blocklist, settings.lookupTimeout(), problems, meters.getIfAvailable());
  }

  /** Spring Cloud Stream 함수 — 설정 저장소 api-gateway.yml 의 {@code subjectBlocks-in-0}. */
  @Bean
  SubjectBlockEvents subjectBlocks(SubjectBlocklist blocklist, Settings settings) {
    return new SubjectBlockEvents(
        blocklist, settings.maxTokenLifetime(), settings.writeTimeout(), Clock.systemUTC());
  }
}
