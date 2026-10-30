package com.pmart.customer.infrastructure;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import com.pmart.customer.application.ProfileService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** order-service 이벤트 구독(order.order.v1). OrderConfirmed 의 첫 구매 혜택 사용만 반영하고 나머지는 inbox 에만 남는다. */
@Component
class OrderEventListener {

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final ProfileService profiles;

  OrderEventListener(EventEnvelopeCodec codec, InboxGuard inbox, ProfileService profiles) {
    this.codec = codec;
    this.inbox = inbox;
    this.profiles = profiles;
  }

  @KafkaListener(topics = "order.order.v1")
  void onMessage(String value) {
    EventEnvelope envelope = codec.decode(value);
    inbox.process(envelope, () -> profiles.applyOrderEvent(envelope));
  }
}
