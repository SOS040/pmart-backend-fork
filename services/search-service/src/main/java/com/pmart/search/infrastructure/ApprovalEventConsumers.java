package com.pmart.search.infrastructure;

import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.search.application.SynonymRegistry;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

/**
 * approval-service 의 ApprovalDecided 구독(토픽 platform.approval.v1, ADR-0073 단계 B) — 동의어 승인 반영.
 *
 * <p>이 서비스는 DB 가 없어 common-messaging 의 inbox 소비({@code EventConsumers}, PmartEventConsumer)를 쓰지
 * 못한다. 그래서 바인더에 그대로 함수를 걸고 멱등은 {@link SynonymRegistry}(승인 ID·규칙 버전)가 맡는다. 그룹·재시도·DLQ 는 다른 업무 바인딩과
 * 같은 기본값 (StreamBindingDefaultsPostProcessor — 함수 이름이 definition 에 있으면). 바인딩 설정(설정 저장소
 * search-service.yml)이 없으면 빈을 만들지 않는다 — 설정을 올리기 전 배포에서도 뜨고, 설정을 올린 뒤 재시작하면 받는다.
 */
@Configuration(proxyBeanMethods = false)
class ApprovalEventConsumers {

  /** platform.approval.v1 — 바인딩 approvalEvents-in-0(설정 저장소). */
  @Bean
  @ConditionalOnProperty(name = "spring.cloud.stream.bindings.approvalEvents-in-0.destination")
  Consumer<Message<byte[]>> approvalEvents(EventEnvelopeCodec codec, SynonymRegistry synonyms) {
    return message ->
        synonyms.onApprovalDecided(
            codec.decode(new String(message.getPayload(), StandardCharsets.UTF_8)));
  }
}
