package com.pmart.gateway.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 경로별 사용자 종류 허용 목록(ADR-0054 §6.1) — 목록 파일을 읽어 필터를 둔다. 모드 기본 RECORD(막지 않고 기록). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserTypeAllowlistFilter.Settings.class)
public class UserTypeAllowlistConfig {

  @Bean
  UserTypeAllowlistFilter userTypeAllowlistFilter(
      UserTypeAllowlistFilter.Settings settings,
      ProblemResponseWriter problems,
      ObjectProvider<MeterRegistry> meters) {
    return new UserTypeAllowlistFilter(
        UserTypeAllowlist.load(), settings.mode(), problems, meters.getIfAvailable());
  }
}
