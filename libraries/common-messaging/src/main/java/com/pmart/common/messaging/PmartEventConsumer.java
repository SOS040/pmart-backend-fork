package com.pmart.common.messaging;

import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.messaging.Message;

/**
 * 업무 이벤트 소비 함수(ADR-0035). Spring Cloud Stream 이 빈 이름으로 바인딩한다: 설정 저장소의 {@code
 * spring.cloud.function.definition} 에 빈 이름, {@code
 * spring.cloud.stream.bindings.<빈이름>-in-0.destination} 에 토픽. 그룹·커밋·재시도·DLQ 는 {@link
 * StreamBindingDefaultsPostProcessor} 가 넣는다.
 *
 * <p>만들 때는 {@link EventConsumers#of}. 메시지 본문은 Kafka 바인더가 넘기는 byte[](봉투 JSON, UTF-8)다.
 */
public final class PmartEventConsumer implements Consumer<Message<byte[]>> {

  private final Consumer<Message<byte[]>> delegate;

  PmartEventConsumer(Consumer<Message<byte[]>> delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  @Override
  public void accept(Message<byte[]> message) {
    delegate.accept(message);
  }
}
