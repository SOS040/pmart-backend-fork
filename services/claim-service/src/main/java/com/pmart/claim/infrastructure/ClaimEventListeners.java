package com.pmart.claim.infrastructure;

import com.pmart.claim.application.ClaimService;
import com.pmart.claim.application.RefundService;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 취소 흐름 구독. order.order.v1(OrderCancelled → 취소 확정·환불 의도), mfc.fulfillment.v1(WorkStopResult → 인계됨이면
 * 취소 불가), order.payment.v1(RefundResult → 환불 결과). 나머지 사건은 inbox 에만 남는다.
 */
@Component
class ClaimEventListeners {

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final ClaimService claims;
  private final RefundService refunds;

  ClaimEventListeners(
      EventEnvelopeCodec codec, InboxGuard inbox, ClaimService claims, RefundService refunds) {
    this.codec = codec;
    this.inbox = inbox;
    this.claims = claims;
    this.refunds = refunds;
  }

  @KafkaListener(topics = "order.order.v1")
  void onOrder(String value) {
    EventEnvelope e = codec.decode(value);
    inbox.process(e, () -> claims.onOrderEvent(e));
  }

  @KafkaListener(topics = "mfc.fulfillment.v1")
  void onFulfillment(String value) {
    EventEnvelope e = codec.decode(value);
    inbox.process(e, () -> claims.onFulfillmentEvent(e));
  }

  @KafkaListener(topics = "order.payment.v1")
  void onPayment(String value) {
    EventEnvelope e = codec.decode(value);
    inbox.process(e, () -> refunds.onPaymentEvent(e));
  }
}
