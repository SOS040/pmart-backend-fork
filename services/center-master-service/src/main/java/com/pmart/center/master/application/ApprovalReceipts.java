package com.pmart.center.master.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 승인 반영 회신 ApplicationReceiptRecorded(ADR-0039, RC-1 — 2026-11-19 사용자 "응").
 * ApprovalDecided(APPROVED· PARTIALLY_APPROVED)를 반영한 같은 inbox 트랜잭션에서 outbox 에 쓴다 — approval-service
 * 가 실행 축(APPLIED·PARTIAL·REJECTED)과 행별 사유를 승인에 남긴다(반영 확인 30초 대기가 없어진다). 반려된 결정에는 회신하지 않는다(반영 요청이
 * 아니다). 본보기 pricing-service PriceApprovalApplier. 한 번에 끝나는 반영이라 순번은 1.
 */
@Component
public class ApprovalReceipts {

  /** 반영 회신 토픽(ADR-0039). 승인 토픽 platform.approval.v1 과 분리. */
  public static final String RECEIPT_TOPIC = "platform.approval-receipt.v1";

  static final String RECEIVER = "center-master-service";

  private final EventPublisher events;
  private final EventEnvelopeCodec codec;

  public ApprovalReceipts(EventPublisher events, EventEnvelopeCodec codec) {
    this.events = events;
    this.codec = codec;
  }

  /** 회신할 결정인가 — 승인(일부 승인 포함)만. */
  public static boolean replies(@Nullable String decision) {
    return "APPROVED".equals(decision) || "PARTIALLY_APPROVED".equals(decision);
  }

  /**
   * 승인 행마다 결과 — rejectedByKey 에 있는 행(키 = 행 식별자)은 REJECTED·그 사유, 나머지는 allApplied 면 APPLIED, 아니면
   * REJECTED·fallbackReason. 전체 결과는 행에서 센다(모두 반영 APPLIED, 일부 PARTIAL, 없음 REJECTED).
   *
   * @param keyOf 승인 행 → 반영 쪽 식별자(초안 ID 등). rejectedByKey 의 키와 같은 것
   */
  public void publish(
      String approvalId,
      JsonNode approvalPayload,
      java.util.function.Function<JsonNode, String> keyOf,
      Map<String, String> rejectedByKey,
      boolean allApplied,
      @Nullable String fallbackReason,
      @Nullable String domainRef) {
    List<Map<String, Object>> lines = new ArrayList<>();
    int applied = 0;
    int i = 0;
    for (JsonNode line : approvalPayload.path("lines")) {
      String lineId = line.hasNonNull("lineId") ? line.get("lineId").asString() : "line-" + i;
      i++;
      String key = keyOf.apply(line);
      String reason = rejectedByKey.get(key);
      boolean ok = reason == null && allApplied;
      Map<String, Object> rl = new LinkedHashMap<>();
      rl.put("lineKey", lineId);
      rl.put("result", ok ? "APPLIED" : "REJECTED");
      rl.put("reasonCode", ok ? null : reason != null ? reason : fallbackReason);
      lines.add(rl);
      if (ok) {
        applied++;
      }
    }
    String result = applied == 0 ? "REJECTED" : applied == lines.size() ? "APPLIED" : "PARTIAL";
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("receiverService", RECEIVER);
    payload.put("receiptSeq", 1);
    payload.put("result", result);
    payload.put("domainRef", domainRef);
    payload.put("reason", result.equals("REJECTED") ? fallbackReason : null);
    payload.put("lines", lines);
    events.publish(
        codec.create(
            events.sourceSystem(),
            "ApplicationReceiptRecorded",
            1,
            "ApplicationReceipt",
            approvalId,
            1,
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            payload),
        RECEIPT_TOPIC);
  }
}
