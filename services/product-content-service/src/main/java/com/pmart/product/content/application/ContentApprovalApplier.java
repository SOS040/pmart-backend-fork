package com.pmart.product.content.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.product.content.domain.ContentProblem;
import com.pmart.product.content.infrastructure.CatalogStore;
import com.pmart.product.content.infrastructure.CatalogStore.ContentRow;
import com.pmart.product.content.infrastructure.CatalogStore.ProjectionRow;
import com.pmart.product.content.infrastructure.DraftStore;
import com.pmart.product.content.infrastructure.DraftStore.DraftRow;
import com.pmart.product.content.infrastructure.DraftStore.ImageRow;
import com.pmart.product.content.infrastructure.DraftStore.ItemRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ApprovalDecided(DISPLAY, targetService=product-content-service) 반영 — 콘텐츠 게시의 유일한 경로(ADR-0014).
 * inbox 트랜잭션 안에서 호출된다(ADR-0009). 승인 행의 draftHash 가 잠근 값과 같은 초안만 새 콘텐츠 버전으로 게시하고
 * ProductContentPublished 를 Outbox 에 쓴다. 반영 결과는 approval_application 에 남겨 같은 승인을 두 번 적용하지 않는다.
 */
@Service
public class ContentApprovalApplier {

  /** ProductContentPublished 토픽: 상품 이벤트와 같은 토픽·키(productId) — 계약 ProductContentPublished.v1. */
  public static final String TOPIC = "commerce.product.v1";

  private static final Logger log = LoggerFactory.getLogger(ContentApprovalApplier.class);

  private final DraftStore drafts;
  private final CatalogStore catalog;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final ApprovalReceipts receipts;

  public ContentApprovalApplier(
      DraftStore drafts,
      CatalogStore catalog,
      EventPublisher events,
      EventEnvelopeCodec codec,
      ObjectMapper mapper,
      Clock clock,
      ApprovalReceipts receipts) {
    this.receipts = receipts;
    this.drafts = drafts;
    this.catalog = catalog;
    this.events = events;
    this.codec = codec;
    this.mapper = mapper;
    this.clock = clock;
  }

  public void handle(EventEnvelope envelope) {
    if (!"ApprovalDecided".equals(envelope.eventType())) {
      return;
    }
    JsonNode p = mapper.valueToTree(envelope.payload());
    JsonNode target = p.path("targetRef");
    if (!"DISPLAY".equals(p.path("type").asString())
        || !"product-content-service".equals(target.path("targetService").asString())) {
      return;
    }
    String approvalId = p.path("approvalId").asString();
    if (catalog.approvalApplied(approvalId)) {
      return;
    }
    String targetId = target.path("targetId").asString();
    String decision = p.path("decision").asString();
    Instant at = clock.instant();
    Map<String, Object> detail = new LinkedHashMap<>();
    String result;
    if (targetId.startsWith(ContentDraftService.DRAFT_PREFIX)
        || targetId.startsWith(ContentDraftService.BATCH_PREFIX)) {
      result = applyLines(p, approvalId, decision, at, detail);
      if (targetId.startsWith(ContentDraftService.BATCH_PREFIX)) {
        settleBatch(targetId.substring(ContentDraftService.BATCH_PREFIX.length()));
      }
    } else {
      result = "IGNORED";
    }
    catalog.recordApplication(approvalId, targetId, result, mapper.writeValueAsString(detail), at);
    log.info("approval {} target {} → {}", approvalId, targetId, result);
    if (ApprovalReceipts.replies(decision)) {
      reply(p, approvalId, targetId, result, detail);
    }
  }

  /** 반영 회신(RC-1, 2026-11-19) — 초안마다 행 결과. 같은 inbox 트랜잭션. */
  private void reply(
      JsonNode p, String approvalId, String targetId, String result, Map<String, Object> detail) {
    if ("IGNORED".equals(result)) {
      receipts.publish(approvalId, p, line -> "", Map.of(), false, "UNKNOWN_TARGET", targetId);
      return;
    }
    Map<String, String> rejected = new LinkedHashMap<>();
    if (detail.get("rejected") instanceof List<?> list) {
      for (Object o : list) {
        String s = String.valueOf(o);
        int c = s.lastIndexOf(':');
        rejected.put(c < 0 ? s : s.substring(0, c), c < 0 ? "REJECTED" : s.substring(c + 1));
      }
    }
    receipts.publish(
        approvalId,
        p,
        line -> line.path("attributes").path("draftId").asString(line.path("lineId").asString()),
        rejected,
        true,
        null,
        targetId);
  }

  private String applyLines(
      JsonNode p, String approvalId, String decision, Instant at, Map<String, Object> detail) {
    int published = 0;
    List<String> rejected = new ArrayList<>();
    for (JsonNode line : p.path("lines")) {
      JsonNode attrs = line.path("attributes");
      String draftId = attrs.path("draftId").asString(line.path("lineId").asString());
      Optional<DraftRow> found = drafts.find(draftId);
      if (found.isEmpty() || !"SUBMITTED".equals(found.get().state())) {
        rejected.add(draftId + ":NOT_SUBMITTED");
        continue;
      }
      DraftRow d = found.get();
      String lineDecision = line.path("decision").asString(decision);
      if (!"APPROVED".equals(decision) || !"APPROVED".equals(lineDecision)) {
        reject(d, approvalId, ContentProblem.APPROVAL_REJECTED, "승인자가 반려했습니다.", at);
        rejected.add(draftId + ":REJECTED");
        continue;
      }
      if (!attrs.path("draftHash").asString("").equals(d.draftHash())) {
        reject(d, approvalId, ContentProblem.APPROVAL_SCOPE_MISMATCH, "승인한 내용과 잠근 초안이 다릅니다.", at);
        rejected.add(draftId + ":SCOPE_MISMATCH");
        continue;
      }
      publish(d, approvalId, at);
      published++;
    }
    detail.put("published", published);
    detail.put("rejected", rejected);
    if (published == 0) {
      return "APPROVED".equals(decision) ? "NOTHING_PUBLISHED" : "REJECTED";
    }
    return rejected.isEmpty() ? "PUBLISHED" : "PARTIALLY_PUBLISHED";
  }

  /** 초안 1건 게시: 새 불변 버전 + 이벤트(같은 트랜잭션). */
  private void publish(DraftRow d, String approvalId, Instant at) {
    String productId = d.productId(); // 잠금 조건이 product_id NOT NULL
    int version = catalog.maxContentVersion(productId) + 1;
    Integer productVersion =
        catalog.projection(productId).map(ProjectionRow::latestProductVersion).orElse(null);
    List<Map<String, Object>> images = new ArrayList<>();
    for (ImageRow i : drafts.images(d.draftId())) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("imageId", i.imageId());
      m.put("role", i.role());
      m.put("sortOrder", i.sortOrder());
      images.add(m);
    }
    catalog.insertContent(
        new ContentRow(
            productId,
            version,
            productVersion,
            d.draftId(),
            approvalId,
            d.name(),
            d.description(),
            d.storageGuide(),
            d.returnGuide(),
            d.notices(),
            d.options(),
            mapper.writeValueAsString(images),
            at,
            d.labelFacts()));
    drafts.markPublished(d.draftId(), approvalId, version, at);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("productId", productId);
    payload.put("contentVersion", version);
    payload.put("publishedAt", at.toString());
    if (productVersion != null) {
      payload.put("productVersion", productVersion);
    }
    payload.put("approvalRef", Map.of("approvalId", approvalId));
    EventEnvelope e =
        codec.create(
            events.sourceSystem(),
            "ProductContentPublished",
            1,
            "ProductContent",
            productId,
            version,
            null,
            EventEnvelope.EventSource.REAL,
            payload);
    events.publish(e, TOPIC);
  }

  private void reject(
      DraftRow d, String approvalId, String code, @Nullable String detail, Instant at) {
    List<ContentProblem> problems = List.of(new ContentProblem(code, null, detail));
    drafts.markRejected(d.draftId(), approvalId, mapper.writeValueAsString(problems), at);
  }

  private void settleBatch(String batchId) {
    Set<String> ids = new HashSet<>();
    for (ItemRow i : drafts.items(batchId)) {
      ids.add(i.draftId());
    }
    long published = 0;
    long open = 0;
    long rejected = 0;
    for (String id : ids) {
      String state = drafts.find(id).map(DraftRow::state).orElse("");
      switch (state) {
        case "PUBLISHED" -> published++;
        case "SUBMITTED" -> open++;
        case "REJECTED" -> rejected++;
        default -> {}
      }
    }
    if (open > 0) {
      return;
    }
    drafts.updateBatchState(
        batchId, published == 0 ? "REJECTED" : rejected == 0 ? "PUBLISHED" : "PARTIALLY_PUBLISHED");
  }
}
