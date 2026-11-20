package com.pmart.promotion.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.promotion.infrastructure.PromotionStore;
import com.pmart.promotion.infrastructure.PromotionStore.PromotionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 행사 승인 반영(ADR-0082). approval-service ApprovalDecided(종류 PROMOTION, 정본 promotion-service,
 * targetRef = 행사 ID)를 inbox 트랜잭션 안에서 받는다. 행 attributes.action 으로 발효(ACTIVATE)·조기 종료(END)를 가른다.
 *
 * <ul>
 *   <li>ACTIVATE 승인 + 잠근 해시 같음 + SUBMITTED → APPROVED(시작 시각부터 적용), 회신 APPLIED
 *   <li>END 승인 + 종료 해시 같음 + APPROVED → ENDED(지금), 회신 APPLIED
 *   <li>해시 다름·상태 어긋남 → 반영 안 함, 회신 REJECTED(SCOPE_MISMATCH·NOT_SUBMITTED)
 *   <li>반려 → ACTIVATE 는 REJECTED(고쳐 다시 제출), END 는 그대로(종료 해시만 비움 — 회신 없음)
 * </ul>
 */
@Service
public class PromotionApprovals {

  public static final String RECEIPT_TOPIC = "platform.approval-receipt.v1";
  private static final Logger log = LoggerFactory.getLogger(PromotionApprovals.class);

  private final PromotionStore store;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public PromotionApprovals(
      PromotionStore store, EventPublisher events, EventEnvelopeCodec codec, Clock clock) {
    this.store = store;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  public static boolean mine(EventEnvelope e) {
    if (!"ApprovalDecided".equals(e.eventType())) {
      return false;
    }
    JsonNode p = e.payload();
    return "PROMOTION".equals(p.path("type").asString())
        && PromotionService.TARGET_SERVICE.equals(
            p.path("targetRef").path("targetService").asString());
  }

  public void handle(EventEnvelope e) {
    JsonNode p = e.payload();
    String approvalId = p.path("approvalId").asString();
    String promotionId = p.path("targetRef").path("targetId").asString();
    boolean approved =
        "APPROVED".equals(p.path("decision").asString())
            || "PARTIALLY_APPROVED".equals(p.path("decision").asString());
    String action = attr(p, "action");
    String hash = attr(p, "draftHash");
    Instant at = clock.instant();
    Optional<PromotionRow> found = store.lock(promotionId);
    if (found.isEmpty()) {
      log.warn("행사 승인 {} — 행사 {} 없음(반영 안 함)", approvalId, promotionId);
      if (approved) {
        reply(p, approvalId, "REJECTED", "NOT_FOUND", null);
      }
      return;
    }
    PromotionRow r = found.get();
    if ("END".equals(action)) {
      if (!approved) {
        store.endSubmitted(promotionId, null, null, at);
        return;
      }
      if (!"APPROVED".equals(r.state()) || hash == null || !hash.equals(r.endHash())) {
        if ("ENDED".equals(r.state())) {
          return; // 재전달
        }
        reply(p, approvalId, "REJECTED", "SCOPE_MISMATCH", null);
        return;
      }
      store.ended(promotionId, at);
      reply(p, approvalId, "APPLIED", null, "promotion:" + promotionId + ":ended");
      log.info("행사 조기 종료 {} (승인 {})", promotionId, approvalId);
      return;
    }
    // ACTIVATE
    if (approvalId.equals(r.approvalId())) {
      return; // 이미 반영(재전달)
    }
    if (!"SUBMITTED".equals(r.state())) {
      log.warn("행사 승인 {} — 행사 {} 이 제출 상태가 아님({})", approvalId, promotionId, r.state());
      if (approved) {
        reply(p, approvalId, "REJECTED", "NOT_SUBMITTED", null);
      }
      return;
    }
    if (!approved) {
      store.decided(promotionId, "REJECTED", approvalId, at);
      log.info("행사 {} 반려(승인 {})", promotionId, approvalId);
      return;
    }
    if (hash == null || !hash.equals(r.draftHash())) {
      store.decided(promotionId, "REJECTED", approvalId, at);
      reply(p, approvalId, "REJECTED", "SCOPE_MISMATCH", null);
      log.warn("행사 승인 {} — 승인한 내용과 잠근 행사가 다름(반영 안 함)", approvalId);
      return;
    }
    store.decided(promotionId, "APPROVED", approvalId, at);
    reply(p, approvalId, "APPLIED", null, "promotion:" + promotionId);
    log.info("행사 발효 승인 {} (승인 {}, {} ~ {})", promotionId, approvalId, r.startsAt(), r.endsAt());
  }

  private static @Nullable String attr(JsonNode p, String name) {
    for (JsonNode line : p.path("lines")) {
      JsonNode v = line.path("attributes").path(name);
      if (!v.isMissingNode() && !v.isNull()) {
        return v.asString();
      }
    }
    return null;
  }

  private void reply(
      JsonNode p,
      String approvalId,
      String result,
      @Nullable String reason,
      @Nullable String domainRef) {
    List<Map<String, Object>> lines = new ArrayList<>();
    int i = 0;
    for (JsonNode line : p.path("lines")) {
      Map<String, Object> rl = new LinkedHashMap<>();
      rl.put("lineKey", line.hasNonNull("lineId") ? line.get("lineId").asString() : "line-" + i);
      rl.put("result", result);
      rl.put("reasonCode", reason);
      lines.add(rl);
      i++;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("receiverService", PromotionService.TARGET_SERVICE);
    payload.put("receiptSeq", 1);
    payload.put("result", result);
    payload.put("domainRef", domainRef);
    payload.put("reason", reason);
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
