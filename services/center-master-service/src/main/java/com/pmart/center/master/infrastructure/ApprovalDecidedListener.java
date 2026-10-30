package com.pmart.center.master.infrastructure;

import com.pmart.center.master.application.CenterService;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** approval-service ApprovalDecided 구독(platform.approval.v1). CENTER_MASTER 승인만 반영한다. */
@Component
class ApprovalDecidedListener {

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final CenterService centers;

  ApprovalDecidedListener(EventEnvelopeCodec codec, InboxGuard inbox, CenterService centers) {
    this.codec = codec;
    this.inbox = inbox;
    this.centers = centers;
  }

  @KafkaListener(topics = "platform.approval.v1")
  void onMessage(String value) {
    EventEnvelope envelope = codec.decode(value);
    inbox.process(envelope, () -> centers.applyApproval(envelope));
  }
}
