package com.pmart.mfc.inventory.infrastructure;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import com.pmart.mfc.inventory.application.SkuProjector;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * product-service 의 SkuIssued·ProductVersionPublished 구독(토픽 commerce.product.v1). 투영 반영과 inbox 기록이
 * 한 트랜잭션이고, 리스너가 정상 반환한 뒤 offset 을 커밋한다(ADR-0009). 같은 토픽의 다른 사건은 반영 없이 inbox 에만 남는다. 취급 레코드는 이벤트로
 * 만들지 않는다(레코드 없음 = OFF).
 */
@Component
class ProductEventsListener {

  static final String TOPIC = "commerce.product.v1";

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final SkuProjector projector;

  ProductEventsListener(EventEnvelopeCodec codec, InboxGuard inbox, SkuProjector projector) {
    this.codec = codec;
    this.inbox = inbox;
    this.projector = projector;
  }

  @KafkaListener(topics = TOPIC)
  void onMessage(String value) {
    EventEnvelope envelope = codec.decode(value);
    inbox.process(envelope, () -> projector.apply(envelope));
  }
}
