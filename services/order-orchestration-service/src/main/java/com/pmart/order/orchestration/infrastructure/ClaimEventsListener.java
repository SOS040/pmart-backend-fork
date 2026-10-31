package com.pmart.order.orchestration.infrastructure;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import com.pmart.order.orchestration.application.CancellationSagaService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** claim-service 사건 구독(order.claim.v1). CancellationRequested 로 S3 를 시작하고 나머지는 inbox 에만 남는다. */
@Component
class ClaimEventsListener {

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final CancellationSagaService sagas;

  ClaimEventsListener(EventEnvelopeCodec codec, InboxGuard inbox, CancellationSagaService sagas) {
    this.codec = codec;
    this.inbox = inbox;
    this.sagas = sagas;
  }

  @KafkaListener(topics = "order.claim.v1")
  void onMessage(String value) {
    EventEnvelope envelope = codec.decode(value);
    inbox.process(envelope, () -> sagas.onClaimEvent(envelope));
  }
}
