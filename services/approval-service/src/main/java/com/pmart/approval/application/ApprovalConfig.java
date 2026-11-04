package com.pmart.approval.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pmart.approval.api.generated.model.ApprovalLine;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
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

  /**
   * 승인 행(ApprovalLine)의 값 없는 수량·금액은 null 이 아니라 생략한다 — 계약은 선택 필드(Money·integer, nullable 아님)라 null 을
   * 받은 백오피스 화면이 깨졌다(BR-45, 2026-11-04). 다른 모델은 계약에 nullable 필드가 있어 전역 non_null 로 두지 않는다.
   */
  @Bean
  JsonMapperBuilderCustomizer approvalLineOmitsNulls() {
    return builder -> builder.addMixIn(ApprovalLine.class, OmitNulls.class);
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private abstract static class OmitNulls {}
}
