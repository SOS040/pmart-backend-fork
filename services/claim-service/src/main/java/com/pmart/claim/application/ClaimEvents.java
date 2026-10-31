package com.pmart.claim.application;

import com.pmart.claim.infrastructure.ClaimStore.ClaimRow;
import com.pmart.claim.infrastructure.ClaimStore.LineRow;
import com.pmart.claim.infrastructure.ClaimStore.RefundRow;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** claim-service 발행 사건(Outbox, 토픽 order.claim.v1). 호출자 트랜잭션 안에서 업무 변경과 함께 쓴다. */
@Component
public class ClaimEvents {

  private final EventPublisher events;
  private final EventEnvelopeCodec codec;

  public ClaimEvents(EventPublisher events, EventEnvelopeCodec codec) {
    this.events = events;
    this.codec = codec;
  }

  public void claimOpened(ClaimRow c, List<LineRow> lines) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("claimId", c.claimId());
    p.put("orderId", c.orderId());
    p.put("type", c.type());
    List<Map<String, Object>> ls = new ArrayList<>();
    for (LineRow l : lines) {
      ls.add(
          Map.of(
              "claimLineId",
              l.claimLineId(),
              "orderLineId",
              l.orderLineId(),
              "qty",
              l.requestedQty()));
    }
    p.put("lines", ls);
    p.put("reason", c.reason());
    p.put("openedAt", c.openedAt().toString());
    publish("ClaimOpened", c.claimId(), c.version(), p);
  }

  /** S3 시작 신호(orchestration 구독). stopRequestId 가 S3 멱등 단위. */
  public void cancellationRequested(ClaimRow c, List<LineRow> lines) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("claimId", c.claimId());
    p.put("orderId", c.orderId());
    p.put("stopRequestId", c.stopRequestId());
    p.put("scope", "FULL");
    List<Map<String, Object>> ls = new ArrayList<>();
    for (LineRow l : lines) {
      ls.add(Map.of("orderLineId", l.orderLineId(), "qty", l.requestedQty()));
    }
    p.put("lines", ls);
    p.put("reason", "CUSTOMER_REQUEST");
    p.put("requestedAt", c.openedAt().toString());
    publish("CancellationRequested", c.claimId(), c.version(), p);
  }

  public void refundIntentCreated(RefundRow r) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("refundIntentId", r.refundIntentId());
    p.put("claimId", r.claimId());
    p.put("orderId", r.orderId());
    p.put("paymentAttemptId", r.paymentAttemptId());
    p.put("amount", r.amount());
    Map<String, Object> basis = new LinkedHashMap<>();
    basis.put("itemAmount", r.itemAmount());
    basis.put("discountShare", 0);
    basis.put("bagFee", r.bagFee());
    basis.put("deliveryTip", r.deliveryTip());
    p.put("basis", basis);
    p.put("createdAt", r.createdAt().toString());
    publish("RefundIntentCreated", r.claimId(), 1, p);
  }

  public void claimClosed(ClaimRow c, String outcome, Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("claimId", c.claimId());
    p.put("orderId", c.orderId());
    p.put("outcome", outcome);
    p.put("closedAt", at.toString());
    publish("ClaimClosed", c.claimId(), c.version() + 1, p);
  }

  private void publish(String type, String claimId, int version, Map<String, Object> payload) {
    EventEnvelope e =
        codec.create(
            events.sourceSystem(),
            type,
            1,
            "Claim",
            claimId,
            version,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            payload);
    events.publish(e, ClaimService.TOPIC);
  }
}
