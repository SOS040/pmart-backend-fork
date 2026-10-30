package com.pmart.product.content.infrastructure;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import com.pmart.product.content.application.ProductProjectionApplier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * product-service 의 상품 이벤트 구독(토픽 commerce.product.v1, 키 productId). ProductVersionPublished 만 반영하고,
 * 같은 토픽의 다른 사건(이 서비스가 내는 ProductContentPublished 포함)은 inbox 에만 남는다.
 */
@Component
class ProductEventsListener {

  static final String TOPIC = "commerce.product.v1";

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final ProductProjectionApplier applier;

  ProductEventsListener(
      EventEnvelopeCodec codec, InboxGuard inbox, ProductProjectionApplier applier) {
    this.codec = codec;
    this.inbox = inbox;
    this.applier = applier;
  }

  @KafkaListener(topics = TOPIC)
  void onMessage(String value) {
    EventEnvelope envelope = codec.decode(value);
    if (!"ProductVersionPublished".equals(envelope.eventType())) {
      return;
    }
    inbox.process(envelope, () -> applier.handle(envelope));
  }
}
