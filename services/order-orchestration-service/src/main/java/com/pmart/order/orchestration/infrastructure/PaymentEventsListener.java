package com.pmart.order.orchestration.infrastructure;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.InboxGuard;
import com.pmart.order.orchestration.application.CheckoutSagaService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * payment-service 결제 사건 구독(order.payment.v1). 승인·실패·불명·불일치·보상 취소 결과가 오면 해당 사가를 깨운다. 사건이 늦거나 없어도
 * sweep 이 결제 시도를 조회해 같은 결과에 이른다.
 */
@Component
class PaymentEventsListener {

  private final EventEnvelopeCodec codec;
  private final InboxGuard inbox;
  private final CheckoutSagaService sagas;

  PaymentEventsListener(EventEnvelopeCodec codec, InboxGuard inbox, CheckoutSagaService sagas) {
    this.codec = codec;
    this.inbox = inbox;
    this.sagas = sagas;
  }

  @KafkaListener(topics = "order.payment.v1")
  void onMessage(String value) {
    EventEnvelope envelope = codec.decode(value);
    inbox.process(envelope, () -> sagas.onPaymentEvent(envelope));
  }
}
