package com.pmart.customer.infrastructure;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import com.pmart.customer.application.ProfileService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** auth-service 이벤트 구독(platform.auth.v1). CustomerAuthenticated 만 반영하고 나머지는 inbox 에만 남는다. */
@Component
class AuthEventListener {

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final ProfileService profiles;

  AuthEventListener(EventEnvelopeCodec codec, InboxGuard inbox, ProfileService profiles) {
    this.codec = codec;
    this.inbox = inbox;
    this.profiles = profiles;
  }

  @KafkaListener(topics = "platform.auth.v1")
  void onMessage(String value) {
    EventEnvelope envelope = codec.decode(value);
    inbox.process(envelope, () -> profiles.applyAuthEvent(envelope));
  }
}
