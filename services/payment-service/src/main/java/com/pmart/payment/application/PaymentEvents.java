package com.pmart.payment.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.observability.CorrelationScope;
import com.pmart.payment.infrastructure.PaymentStore.Attempt;
import com.pmart.payment.infrastructure.PaymentStore.Cancel;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 결제 사건 발행(Outbox, 호출자 트랜잭션 안). 토픽은 계약 {@code order.payment.v1}(집합 이름 PaymentAttempt 기본 토픽이 아님). 카드
 * 정보·시크릿 키는 싣지 않는다.
 */
@Component
public class PaymentEvents {

  public static final String TOPIC = "order.payment.v1";

  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ObjectProvider<Tracer> tracer;

  public PaymentEvents(
      EventPublisher events, EventEnvelopeCodec codec, ObjectProvider<Tracer> tracer) {
    this.events = events;
    this.codec = codec;
    this.tracer = tracer;
  }

  public void approved(Attempt a, String method, Instant approvedAt) {
    Map<String, Object> p = base(a);
    p.put("pgTransactionId", a.paymentKey());
    p.put("amount", a.amount());
    p.put("currency", a.currency());
    p.put("approvedAt", approvedAt.toString());
    p.put("method", method);
    publish("PaymentApproved", a, p);
  }

  public void failed(Attempt a, String code, @Nullable String message, Instant at) {
    Map<String, Object> p = base(a);
    p.put("code", code);
    if (message != null) {
      p.put("message", message);
    }
    p.put("failedAt", at.toString());
    publish("PaymentFailed", a, p);
  }

  public void unknown(Attempt a, int inquiryCount, Instant nextInquiryAt, boolean escalated) {
    Map<String, Object> p = base(a);
    p.put("inquiryCount", inquiryCount);
    p.put("nextInquiryAt", nextInquiryAt.toString());
    p.put("escalated", escalated);
    publish("PaymentResultUnknown", a, p);
  }

  public void mismatch(Attempt a, @Nullable Long pgAmount, String field) {
    Map<String, Object> p = base(a);
    p.put("expectedAmount", a.amount());
    p.put("approvedAmount", pgAmount == null ? 0 : pgAmount);
    p.put("field", field);
    publish("PaymentMismatch", a, p);
  }

  public void cancelResult(
      Attempt a,
      Cancel c,
      String result,
      @Nullable String failureCode,
      long cumulativeRefunded,
      long remainingLimit,
      Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    if ("COMPENSATION".equals(c.kind())) {
      p.put("paymentAttemptId", a.id());
      p.put("sagaId", c.sagaId() == null ? a.sagaId() : c.sagaId());
      p.put("pgTransactionId", a.paymentKey());
      p.put("amount", c.amount());
      p.put("result", result);
      if (failureCode != null) {
        p.put("failureCode", failureCode);
      }
      p.put("resultAt", at.toString());
      publish("CompensationCancelResult", a, p);
      return;
    }
    p.put("refundRequestId", c.requestId());
    p.put("refundIntentId", c.refundIntentId());
    p.put("paymentAttemptId", a.id());
    p.put("pgTransactionId", a.paymentKey());
    p.put("amount", c.amount());
    p.put("result", result);
    if (failureCode != null) {
      p.put("failureCode", failureCode);
    }
    p.put("cumulativeRefunded", cumulativeRefunded);
    p.put("remainingLimit", Math.max(0, remainingLimit));
    p.put("resultAt", at.toString());
    publish("RefundResult", a, p);
  }

  private static Map<String, Object> base(Attempt a) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("paymentAttemptId", a.id());
    p.put("orderRef", a.orderRef());
    p.put("sagaId", a.sagaId());
    return p;
  }

  /**
   * 결제 시도 사건의 correlationId = 시도에 저장된 sagaId(작업 C). 결제 승인은 앱에서 오는 새 요청이라 요청 correlationId 는 사가와
   * 무관하다. 환불 결과(RefundResult)는 claim 흐름(S3, correlationId = claimId)이라 요청의 값을 그대로 쓴다.
   */
  private void publish(String type, Attempt a, Map<String, Object> payload) {
    String correlation = "RefundResult".equals(type) ? null : a.sagaId();
    try (CorrelationScope c = CorrelationScope.open(tracer.getIfAvailable(), correlation)) {
      doPublish(type, a, payload);
    }
  }

  private void doPublish(String type, Attempt a, Map<String, Object> payload) {
    EventEnvelope e =
        codec.create(
            events.sourceSystem(),
            type,
            1,
            "PaymentAttempt",
            a.id(),
            a.version() + 1L,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            payload);
    events.publish(e, TOPIC);
  }
}
