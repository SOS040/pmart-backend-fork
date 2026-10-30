package com.pmart.cart.infrastructure;

import com.pmart.cart.application.CartService;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** order-service 이벤트 구독(order.order.v1). OrderConfirmed 만 반영(주문분 장바구니 정리)하고 나머지는 inbox 에만 남는다. */
@Component
class OrderEventListener {

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final CartService carts;

  OrderEventListener(EventEnvelopeCodec codec, InboxGuard inbox, CartService carts) {
    this.codec = codec;
    this.inbox = inbox;
    this.carts = carts;
  }

  @KafkaListener(topics = "order.order.v1")
  void onMessage(String value) {
    EventEnvelope envelope = codec.decode(value);
    inbox.process(
        envelope,
        () -> {
          if ("OrderConfirmed".equals(envelope.eventType())) {
            carts.applyOrderConfirmed(envelope);
          }
        });
  }
}
