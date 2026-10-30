package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import com.pmart.mfc.inbound.application.InitialStockService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * approval-service ApprovalDecided 구독(platform.approval.v1). INITIAL_STOCK 승인만 반입 기록으로 만든다. 반영과
 * inbox 기록이 한 트랜잭션이고, mfc-inventory 전기는 트랜잭션 밖 주기 작업이 한다.
 */
@Component
class ApprovalDecidedListener {

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final InitialStockService loads;

  ApprovalDecidedListener(EventEnvelopeCodec codec, InboxGuard inbox, InitialStockService loads) {
    this.codec = codec;
    this.inbox = inbox;
    this.loads = loads;
  }

  @KafkaListener(topics = "platform.approval.v1")
  void onMessage(String value) {
    EventEnvelope envelope = codec.decode(value);
    inbox.process(envelope, () -> loads.applyApproval(envelope));
  }
}
