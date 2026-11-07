package com.pmart.mfc.quality.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.mfc.quality.domain.QualityRules.DisposalLine;
import com.pmart.mfc.quality.domain.QualityRules.Line;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** 품질 사건(토픽 mfc.quality.v1, 호출자 트랜잭션 안 Outbox). 재고 반영이 끝난 사실만. */
@Component
public class QualityEvents {

  public static final String TOPIC = "mfc.quality.v1";

  private final EventPublisher events;
  private final EventEnvelopeCodec codec;

  public QualityEvents(EventPublisher events, EventEnvelopeCodec codec) {
    this.events = events;
    this.codec = codec;
  }

  /** 줄마다 QualityDecisionMade(스키마가 SKU·LOT 한 건) — 집계 = 판정, 버전 = 판정 버전 × 1000 + 줄 번호. */
  public void decisionMade(
      String decisionId,
      String mfcId,
      String decision,
      String reason,
      List<Line> lines,
      String actorId,
      @Nullable String approverId,
      int version,
      Instant at) {
    int i = 0;
    for (Line l : lines) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("decisionId", decisionId);
      m.put("mfcId", mfcId);
      m.put("sku", l.sku());
      m.put("lot", l.lotId());
      m.put("locationCode", l.locationCode());
      m.put("decision", decision);
      m.put("qty", l.qty());
      m.put("reason", reason);
      if (approverId != null) {
        m.put("approverRef", approverId);
      }
      m.put("decidedAt", at.toString());
      events.publish(
          codec.create(
              events.sourceSystem(),
              "QualityDecisionMade",
              1,
              "QualityDecision",
              decisionId,
              version * 1000 + (++i),
              new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, actorId),
              EventEnvelope.EventSource.REAL,
              m),
          TOPIC);
    }
  }

  public void disposalApproved(
      String disposalId,
      String mfcId,
      List<DisposalLine> lines,
      String approverId,
      String approvalRef,
      @Nullable String reason,
      int version,
      Instant at) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("centerType", "MFC");
    m.put("centerId", mfcId);
    m.put("disposalId", disposalId);
    m.put(
        "lines",
        lines.stream()
            .filter(d -> d.approvedQty() > 0)
            .map(
                d -> {
                  Map<String, Object> x = new LinkedHashMap<>();
                  x.put("sku", d.sku());
                  x.put("lot", d.lotId());
                  x.put("locationCode", d.locationCode());
                  x.put("qty", d.approvedQty());
                  return x;
                })
            .toList());
    if (reason != null) {
      m.put("reason", reason);
    }
    m.put("approverRef", approverId);
    m.put("approvalRef", Map.of("approvalId", approvalRef, "approverId", approverId));
    m.put("approvedAt", at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "DisposalApproved",
            1,
            "Disposal",
            disposalId,
            version,
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, approverId),
            EventEnvelope.EventSource.REAL,
            m),
        TOPIC);
  }
}
