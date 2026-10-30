package com.pmart.pricing.infrastructure;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import com.pmart.pricing.application.PriceApprovalApplier;
import com.pmart.pricing.application.SkuProjector;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 구독: approval-service ApprovalDecided(platform.approval.v1), product-service
 * SkuIssued·ProductVersionPublished(commerce.product.v1). 반영과 inbox 기록이 한 트랜잭션이고, 리스너가 정상 반환한 뒤
 * offset 을 커밋한다(ADR-0009). 쓰지 않는 사건은 반영 없이 inbox 에만 남는다.
 */
@Component
class EventListeners {

  static final String APPROVAL_TOPIC = "platform.approval.v1";
  static final String PRODUCT_TOPIC = "commerce.product.v1";

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final PriceApprovalApplier approvals;
  private final SkuProjector skus;

  EventListeners(
      EventEnvelopeCodec codec,
      InboxGuard inbox,
      PriceApprovalApplier approvals,
      SkuProjector skus) {
    this.codec = codec;
    this.inbox = inbox;
    this.approvals = approvals;
    this.skus = skus;
  }

  @KafkaListener(topics = APPROVAL_TOPIC)
  void onApproval(String value) {
    EventEnvelope envelope = codec.decode(value);
    inbox.process(envelope, () -> approvals.handle(envelope));
  }

  @KafkaListener(topics = PRODUCT_TOPIC)
  void onProduct(String value) {
    EventEnvelope envelope = codec.decode(value);
    inbox.process(envelope, () -> skus.apply(envelope));
  }
}
