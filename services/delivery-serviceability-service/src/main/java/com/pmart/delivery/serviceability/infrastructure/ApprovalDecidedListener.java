package com.pmart.delivery.serviceability.infrastructure;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import com.pmart.delivery.serviceability.application.ServiceabilityService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * approval-service ApprovalDecided 구독(platform.approval.v1). SERVICEABILITY 승인만 반영하고 나머지는 inbox 에만
 * 남는다.
 */
@Component
class ApprovalDecidedListener {

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final ServiceabilityService service;

  ApprovalDecidedListener(
      EventEnvelopeCodec codec, InboxGuard inbox, ServiceabilityService service) {
    this.codec = codec;
    this.inbox = inbox;
    this.service = service;
  }

  @KafkaListener(topics = "platform.approval.v1")
  void onMessage(String value) {
    EventEnvelope envelope = codec.decode(value);
    inbox.process(envelope, () -> service.applyApproval(envelope));
  }
}
