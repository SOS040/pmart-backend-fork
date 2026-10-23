package com.pmart.common.messaging;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 서비스가 쓰는 발행 진입점. 봉투를 만들고 Outbox 에 쓴다(호출자 트랜잭션 안에서).
 *
 * <pre>
 * // @Transactional 메서드 안에서
 * eventPublisher.publish("OrderConfirmed", 1, "Order", orderId, newVersion, actor, payload);
 * </pre>
 */
public class EventPublisher {

  private final ObjectProvider<OutboxWriter> outbox;
  private final EventEnvelopeCodec codec;
  private final String sourceSystem;

  public EventPublisher(
      ObjectProvider<OutboxWriter> outbox, EventEnvelopeCodec codec, String sourceSystem) {
    this.outbox = outbox;
    this.codec = codec;
    this.sourceSystem = sourceSystem;
  }

  public String sourceSystem() {
    return sourceSystem;
  }

  public EventEnvelope publish(
      String eventType,
      int schemaVersion,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      EventEnvelope.@Nullable Actor actor,
      Object payload) {
    return publish(
        eventType,
        schemaVersion,
        aggregateType,
        aggregateId,
        aggregateVersion,
        actor,
        EventEnvelope.EventSource.REAL,
        payload);
  }

  public EventEnvelope publish(
      String eventType,
      int schemaVersion,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      EventEnvelope.@Nullable Actor actor,
      EventEnvelope.EventSource source,
      Object payload) {
    EventEnvelope envelope =
        codec.create(
            sourceSystem,
            eventType,
            schemaVersion,
            aggregateType,
            aggregateId,
            aggregateVersion,
            actor,
            source,
            payload);
    writer().append(envelope);
    return envelope;
  }

  /** 이미 만든 봉투를 지정 토픽으로. */
  public void publish(EventEnvelope envelope, String topic) {
    writer().append(envelope, topic);
  }

  private OutboxWriter writer() {
    OutboxWriter w = outbox.getIfAvailable();
    if (w == null) {
      throw new IllegalStateException(
          "OutboxWriter 없음: DataSource/JdbcTemplate 과 pmart.messaging.domain 설정이 필요합니다.");
    }
    return w;
  }
}
