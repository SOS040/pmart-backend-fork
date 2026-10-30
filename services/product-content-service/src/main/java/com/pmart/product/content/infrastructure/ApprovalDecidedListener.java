package com.pmart.product.content.infrastructure;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import com.pmart.product.content.application.ContentApprovalApplier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * approval-service 의 ApprovalDecided 구독(토픽 platform.approval.v1). 업무 반영과 inbox 기록이 한 트랜잭션이고, 리스너가
 * 정상 반환한 뒤 offset 을 커밋한다(ADR-0009). DISPLAY·이 서비스 대상이 아닌 승인은 반영 없이 inbox 에만 남는다.
 */
@Component
class ApprovalDecidedListener {

  static final String TOPIC = "platform.approval.v1";

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final ContentApprovalApplier applier;

  ApprovalDecidedListener(
      EventEnvelopeCodec codec, InboxGuard inbox, ContentApprovalApplier applier) {
    this.codec = codec;
    this.inbox = inbox;
    this.applier = applier;
  }

  @KafkaListener(topics = TOPIC)
  void onMessage(String value) {
    EventEnvelope envelope = codec.decode(value);
    inbox.process(envelope, () -> applier.handle(envelope));
  }
}
