package com.pmart.storefront.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.storefront.infrastructure.LayoutStore;
import com.pmart.storefront.infrastructure.LayoutStore.DraftRow;
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
import tools.jackson.databind.ObjectMapper;

/**
 * 진열 편성 게시(2026-11-20 ADR-0075 ② — BO-05). approval-service 의 ApprovalDecided(종류 DISPLAY, 정본
 * storefront-service, targetRef = 초안 ID)를 inbox 트랜잭션 안에서 받는다.
 *
 * <ul>
 *   <li>APPROVED·승인 행의 draftHash = 잠근 해시 → 새 진열 버전(layout_version) 게시, 초안 PUBLISHED, 반영 회신 APPLIED
 *   <li>해시가 다름·제출 상태가 아님 → 게시하지 않고 회신 REJECTED(SCOPE_MISMATCH·NOT_SUBMITTED)
 *   <li>REJECTED → 초안 REJECTED(고쳐서 다시 제출), 회신 없음(반영 요청이 아님)
 * </ul>
 *
 * 같은 승인은 한 번만(버전의 approval_id 고유 + inbox). 회신은 ADR-0039 ApplicationReceiptRecorded(같은 outbox 트랜잭션).
 */
@Service
public class LayoutPublisher {

  public static final String RECEIPT_TOPIC = "platform.approval-receipt.v1";
  private static final Logger log = LoggerFactory.getLogger(LayoutPublisher.class);

  private final LayoutStore store;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ObjectMapper mapper;
  private final Clock clock;

  public LayoutPublisher(
      LayoutStore store,
      EventPublisher events,
      EventEnvelopeCodec codec,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.codec = codec;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** 이 서비스 대상의 DISPLAY 결정인가(다른 결정은 inbox 에 남기지 않고 건너뛴다). */
  public static boolean mine(EventEnvelope e) {
    if (!"ApprovalDecided".equals(e.eventType())) {
      return false;
    }
    JsonNode p = e.payload();
    return "DISPLAY".equals(p.path("type").asString())
        && LayoutService.TARGET_SERVICE.equals(
            p.path("targetRef").path("targetService").asString());
  }

  public void handle(EventEnvelope e) {
    JsonNode p = e.payload();
    String approvalId = p.path("approvalId").asString();
    String draftId = p.path("targetRef").path("targetId").asString();
    String decision = p.path("decision").asString();
    Instant at = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    if (store.versionForApproval(approvalId)) {
      return; // 이미 게시(재전달)
    }
    Optional<DraftRow> found = store.lockDraft(draftId);
    boolean approved = "APPROVED".equals(decision) || "PARTIALLY_APPROVED".equals(decision);
    if (found.isEmpty() || !"SUBMITTED".equals(found.get().state())) {
      log.warn("진열 편성 승인 {} — 초안 {} 이 제출 상태가 아님(반영 안 함)", approvalId, draftId);
      if (approved) {
        reply(p, approvalId, "REJECTED", "NOT_SUBMITTED", null);
      }
      return;
    }
    DraftRow d = found.get();
    if (!approved) {
      store.rejected(draftId, approvalId, "APPROVAL_REJECTED", at);
      log.info("진열 편성 초안 {} 반려(승인 {})", draftId, approvalId);
      return;
    }
    String lineHash = lineHash(p);
    if (lineHash == null || !lineHash.equals(d.draftHash())) {
      store.rejected(draftId, approvalId, "SCOPE_MISMATCH", at);
      reply(p, approvalId, "REJECTED", "SCOPE_MISMATCH", null);
      log.warn("진열 편성 승인 {} — 승인한 내용과 잠근 초안이 다름(반영 안 함)", approvalId);
      return;
    }
    int version = store.publish(draftId, approvalId, d.body(), at);
    store.published(draftId, approvalId, version, at);
    reply(p, approvalId, "APPLIED", null, "layout:" + version);
    log.info("진열 편성 게시 layoutVersion={} draft={} approval={}", version, draftId, approvalId);
  }

  private static @Nullable String lineHash(JsonNode p) {
    for (JsonNode line : p.path("lines")) {
      JsonNode h = line.path("attributes").path("draftHash");
      if (!h.isMissingNode() && !h.isNull()) {
        return h.asString();
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
    payload.put("receiverService", LayoutService.TARGET_SERVICE);
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
