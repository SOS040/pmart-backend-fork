package com.pmart.common.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.stream.binder.kafka.utils.DlqDestinationResolver;

/**
 * Kafka 바인더 DLQ 목적지(ADR-0030·0035): 재시도를 다 쓴 레코드는 <b>원래 토픽 + {@code .DLT}</b> 로 간다(바인딩에 토픽이 여럿이어도
 * 각자). 보내기 전에 이 서비스 DB {@code dead_letter} 에 OPEN 으로 남긴다({@link DeadLetters#record}, 실패해도 예외를 던지지
 * 않는다 — DLT 사본은 그대로 간다). DLQ 가 켜진 바인딩(업무 바인딩)에만 쓰인다 — Bus 바인딩은 DLQ 가 꺼져 있다.
 */
final class PmartDlqDestinationResolver implements DlqDestinationResolver {

  private final ObjectProvider<DeadLetters> deadLetters;

  PmartDlqDestinationResolver(ObjectProvider<DeadLetters> deadLetters) {
    this.deadLetters = deadLetters;
  }

  @Override
  public String apply(ConsumerRecord<?, ?> record, @Nullable Exception failure) {
    DeadLetters d = deadLetters.getIfAvailable();
    if (d != null) {
      d.record(record, failure == null ? new IllegalStateException("원인 없음") : failure);
    }
    return Topics.deadLetter(record.topic());
  }
}
