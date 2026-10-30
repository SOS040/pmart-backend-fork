package com.pmart.storefront.infrastructure;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import com.pmart.storefront.application.DisplayProjector;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 상품(commerce.product.v1)·가격(commerce.pricing.v1) 사건 구독 → 진열 투영. */
@Component
class CatalogEventListener {

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final DisplayProjector projector;

  CatalogEventListener(EventEnvelopeCodec codec, InboxGuard inbox, DisplayProjector projector) {
    this.codec = codec;
    this.inbox = inbox;
    this.projector = projector;
  }

  @KafkaListener(topics = {"commerce.product.v1", "commerce.pricing.v1"})
  void onMessage(String value) {
    EventEnvelope envelope = codec.decode(value);
    inbox.process(envelope, () -> projector.apply(envelope));
  }
}
