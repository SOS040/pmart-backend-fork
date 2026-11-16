package com.pmart.product.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.product.domain.BarcodeType;
import com.pmart.product.domain.DraftFacts;
import com.pmart.product.domain.DraftProblem;
import com.pmart.product.domain.SkuCodes;
import com.pmart.product.infrastructure.CatalogStore;
import com.pmart.product.infrastructure.CatalogStore.LogisticsRow;
import com.pmart.product.infrastructure.CatalogStore.ProductRow;
import com.pmart.product.infrastructure.CatalogStore.SkuRow;
import com.pmart.product.infrastructure.CommandStore;
import com.pmart.product.infrastructure.DraftStore;
import com.pmart.product.infrastructure.DraftStore.DraftRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ApprovalDecided(PRODUCT, targetService=product-service) 반영. inbox 트랜잭션 안에서 호출된다(ADR-0009). 승인된
 * 행만, 잠근 draftHash 와 같은 초안만 반영하고 SKU 를 발급한다(재사용 없는 전역 일련번호). 반영 결과는 approval_application 에 남겨 같은
 * 승인을 두 번 적용하지 않는다.
 */
@Service
public class ApprovalApplier {

  private static final Logger log = LoggerFactory.getLogger(ApprovalApplier.class);

  /** 상품 사건 토픽(SkuIssued·ProductVersionPublished·ProductSaleStateChanged). */
  public static final String PRODUCT_TOPIC = "commerce.product.v1";

  private final CategoryTreeService trees;
  private final ProductDraftService draftService;
  private final DraftStore drafts;
  private final CatalogStore catalog;
  private final ProductSnapshots snapshots;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final CommandStore commands;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ApprovalApplier(
      CategoryTreeService trees,
      ProductDraftService draftService,
      DraftStore drafts,
      CatalogStore catalog,
      ProductSnapshots snapshots,
      EventPublisher events,
      EventEnvelopeCodec codec,
      CommandStore commands,
      ObjectMapper mapper,
      Clock clock) {
    this.trees = trees;
    this.draftService = draftService;
    this.drafts = drafts;
    this.catalog = catalog;
    this.snapshots = snapshots;
    this.events = events;
    this.codec = codec;
    this.commands = commands;
    this.mapper = mapper;
    this.clock = clock;
  }

  public void handle(EventEnvelope envelope) {
    if (!"ApprovalDecided".equals(envelope.eventType())) {
      return;
    }
    JsonNode p = mapper.valueToTree(envelope.payload());
    JsonNode target = p.path("targetRef");
    if (!"PRODUCT".equals(p.path("type").asString())
        || !"product-service".equals(target.path("targetService").asString())) {
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
    if (targetId.startsWith(CategoryTreeService.TARGET_PREFIX)) {
      int version =
          Integer.parseInt(targetId.substring(CategoryTreeService.TARGET_PREFIX.length()));
      String hash = p.path("lines").path(0).path("attributes").path("draftHash").asString(null);
      result = trees.applyDecision(version, decision, approvalId, hash, at);
    } else if (targetId.startsWith(SaleStateService.TARGET_PREFIX)) {
      result = recordSaleStateGrant(p, approvalId, decision, targetId, at);
    } else if (targetId.startsWith(ProductDraftService.DRAFT_PREFIX)
        || targetId.startsWith(ProductDraftService.BATCH_PREFIX)) {
      result = applyDrafts(p, approvalId, decision, at, detail);
      if (targetId.startsWith(ProductDraftService.BATCH_PREFIX)) {
        settleBatch(targetId.substring(ProductDraftService.BATCH_PREFIX.length()));
      }
    } else {
      result = "IGNORED";
    }
    catalog.recordApplication(approvalId, targetId, result, mapper.writeValueAsString(detail), at);
    log.info("approval {} target {} → {}", approvalId, targetId, result);
  }

  private String applyDrafts(
      JsonNode p, String approvalId, String decision, Instant at, Map<String, Object> detail) {
    int applied = 0;
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
        reject(d, approvalId, "APPROVAL_REJECTED", "승인자가 반려했습니다.", at);
        rejected.add(draftId + ":REJECTED");
        continue;
      }
      if (!attrs.path("draftHash").asString("").equals(d.draftHash())) {
        reject(d, approvalId, "APPROVAL_SCOPE_MISMATCH", "승인한 내용과 잠근 초안이 다릅니다.", at);
        rejected.add(draftId + ":SCOPE_MISMATCH");
        continue;
      }
      Optional<DraftProblem> problem = apply(d, approvalId, at);
      if (problem.isPresent()) {
        reject(d, approvalId, problem.get().code(), problem.get().detail(), at);
        rejected.add(draftId + ":" + problem.get().code());
      } else {
        applied++;
      }
    }
    detail.put("applied", applied);
    detail.put("rejected", rejected);
    if (applied == 0) {
      return "APPROVED".equals(decision) ? "NOTHING_APPLIED" : "REJECTED";
    }
    return rejected.isEmpty() ? "APPLIED" : "PARTIALLY_APPLIED";
  }

  /** 초안 1건 반영. 반영할 수 없으면 사유를 돌려주고 아무것도 쓰지 않는다. */
  private Optional<DraftProblem> apply(DraftRow d, String approvalId, Instant at) {
    JsonNode in = mapper.readTree(d.input());
    DraftFacts f = draftService.facts(in);
    List<String> categoryIds = new ArrayList<>();
    mapper.readTree(d.resolvedCategories()).forEach(c -> categoryIds.add(c.asString()));
    JsonNode skuIn = in.path("skus").path(0);
    @Nullable String requestedSaleState = in.path("product").path("saleState").asString(null);

    // 갱신 대상: targetProductId(원천 키 없는 상품 속성 변경) 또는 원천 키 연결(BR-39)
    Optional<CatalogStore.SourceLinkRow> link =
        draftService.updateTarget(in, d.sourceSystem(), d.sourceKey());
    Optional<ProductRow> existing = link.flatMap(l -> catalog.findProduct(l.productId()));
    List<String> ownSkus =
        existing
            .map(e -> catalog.skusOf(e.productId()).stream().map(SkuRow::sku).toList())
            .orElse(List.of());

    // 승인과 반영 사이에 다른 SKU 가 바코드를 먼저 가져갔으면 자동 선택하지 않고 반려(BO-03)
    LinkedHashSet<String> barcodes = new LinkedHashSet<>();
    f.barcodes().forEach(b -> barcodes.add(BarcodeType.normalize(b)));
    for (String b : barcodes) {
      Optional<String> owner = catalog.activeBarcodeOwner(b);
      if (owner.isPresent() && !ownSkus.contains(owner.get())) {
        return Optional.of(
            DraftProblem.of(
                "BARCODE_CONFLICT",
                "skus[0].barcodes",
                b + " 는 이미 " + owner.get() + " 에 연결돼 있습니다."));
      }
    }

    String productId;
    int version;
    int rowVersion;
    List<String> issued = new ArrayList<>();
    String sku;
    if (existing.isPresent()) {
      ProductRow e = existing.get();
      SkuRow current = catalog.skusOf(e.productId()).get(0);
      if (!current.unitType().equals(f.unitType()) || current.unitsPerSku() != f.unitsPerSku()) {
        // E/B·구성 낱개 수가 바뀌면 다른 실물 — 기존 SKU 유지/신규 발급 기준이 미승인이라 반영하지 않는다(BO-03 2)-4)
        return Optional.of(
            DraftProblem.of(
                "SKU_IDENTITY_CHANGED",
                "skus[0]",
                "판매 단위·구성 낱개 수 변경은 별도 심사 대상입니다(" + current.sku() + ")."));
      }
      productId = e.productId();
      version = e.currentVersion() + 1;
      rowVersion = e.rowVersion() + 1;
      catalog.updateProduct(
          productId,
          e.rowVersion(),
          f.internalName(),
          categoryIds.get(0),
          // 재적재 승인이 판매 상태를 말하지 않으면 현재 상태 유지(판매중지를 몰래 풀지 않는다)
          requestedSaleState != null ? requestedSaleState : e.saleState(),
          version,
          at);
      sku = current.sku();
      catalog.updateSkuAttributes(
          sku,
          f.weightG(),
          intOrNull(skuIn, "volumeMl"),
          f.storageType(),
          skuIn.path("alcohol").asBoolean(false),
          f.shelfMinRemainingDays(),
          f.shelfMaxDaysSinceManufacture(),
          f.dimensions().complete());
      for (String old : catalog.activeBarcodes(sku)) {
        if (!barcodes.contains(old)) {
          catalog.retireBarcode(old, sku, at);
        }
      }
    } else {
      String baseProductId = baseProduct(in, f, at);
      productId = "PRD-" + UUID.randomUUID();
      version = 1;
      rowVersion = 1;
      catalog.insertProduct(
          new ProductRow(
              productId,
              baseProductId,
              f.internalName(),
              categoryIds.get(0),
              requestedSaleState != null ? requestedSaleState : "ON_SALE",
              version,
              1,
              at,
              at));
      long serial = catalog.nextSerial();
      sku = SkuCodes.format(f.unitType(), serial);
      catalog.insertSku(
          new SkuRow(
              sku,
              serial,
              f.unitType(),
              productId,
              baseProductId,
              f.unitsPerSku(),
              f.weightG(),
              intOrNull(skuIn, "volumeMl"),
              f.storageType(),
              skuIn.path("alcohol").asBoolean(false),
              "ACTIVE",
              approvalId,
              at,
              f.shelfMinRemainingDays(),
              f.shelfMaxDaysSinceManufacture(),
              f.dimensions().complete()));
      issued.add(sku);
    }
    List<String> active = catalog.activeBarcodes(sku);
    for (String b : barcodes) {
      if (!active.contains(b)) {
        catalog.insertBarcode(b, sku, approvalId, at);
      }
    }
    JsonNode lg = skuIn.path("logistics");
    if (lg.isObject() && !lg.isEmpty()) {
      catalog.insertLogistics(
          sku,
          new LogisticsRow(
              intOrNull(lg, "unitsPerBox"),
              intOrNull(lg, "boxesPerPallet"),
              intOrNull(lg, "ti"),
              intOrNull(lg, "hi")),
          approvalId,
          at);
    }
    catalog.replaceCategories(productId, categoryIds);
    List<String[]> options = new ArrayList<>();
    f.filterOptions().forEach(o -> options.add(new String[] {o.filterGroupId(), o.option()}));
    catalog.replaceFilterOptions(productId, options);

    Map<String, Object> snapshot = snapshots.build(productId, version, approvalId, at);
    catalog.insertVersion(productId, version, approvalId, mapper.writeValueAsString(snapshot), at);
    String skusJson = mapper.writeValueAsString(List.of(sku));
    if (d.sourceSystem() != null && d.sourceKey() != null) {
      catalog.upsertSourceLink(
          d.sourceSystem(), d.sourceKey(), productId, skusJson, d.draftId(), at);
    }
    drafts.markApplied(d.draftId(), approvalId, productId, skusJson, at);

    for (String s : issued) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("sku", s);
      payload.put("baseProductId", snapshot.get("baseProductId"));
      payload.put("productId", productId);
      payload.put("version", version);
      payload.put("issuedAt", at.toString());
      payload.put("approvalRef", Map.of("approvalId", approvalId));
      // 계약 토픽은 commerce.product.v1 (집합 이름 Sku 의 기본 토픽 commerce.sku.v1 이 아님)
      events.publish(
          codec.create(
              events.sourceSystem(),
              "SkuIssued",
              1,
              "Sku",
              s,
              1,
              null,
              EventEnvelope.EventSource.REAL,
              payload),
          PRODUCT_TOPIC);
    }
    Map<String, Object> published = new LinkedHashMap<>(snapshot);
    published.put("effectiveAt", at.toString());
    // 판매 상태 비교용 행 버전 — ProductSaleStateChanged.version 과 같은 카운터(소비자가 시각 대신 이 값으로 비교)
    published.put("rowVersion", rowVersion);
    published.put("approvalRef", Map.of("approvalId", approvalId));
    if (d.sourceSystem() != null && d.sourceKey() != null) {
      published.put("sources", List.of(Map.of("system", d.sourceSystem(), "key", d.sourceKey())));
    }
    published.remove("approvalId");
    published.remove("publishedAt");
    events.publish("ProductVersionPublished", 1, "Product", productId, version, null, published);
    return Optional.empty();
  }

  /**
   * 판매 상태 변경 승인(targetRef {@code SALE-STATE:{productId}}, 행 attributes {@code {saleState}}) 수신 기록.
   * 반영은 판매 상태 변경 명령이 이 승인을 참조할 때 한다({@link SaleStateService}).
   */
  private String recordSaleStateGrant(
      JsonNode p, String approvalId, String decision, String targetId, Instant at) {
    String productId = targetId.substring(SaleStateService.TARGET_PREFIX.length());
    String state = p.path("lines").path(0).path("attributes").path("saleState").asString("");
    commands.insertGrant(approvalId, productId, state, decision, at);
    return "APPROVED".equals(decision) ? "GRANTED" : "REJECTED";
  }

  private String baseProduct(JsonNode in, DraftFacts f, Instant at) {
    if (f.baseProductId() != null) {
      return f.baseProductId();
    }
    String key = in.path("product").path("baseProductKey").asString(null);
    if (key != null && !key.isBlank()) {
      return catalog
          .baseProductByKey(key)
          .orElseGet(() -> catalog.insertBaseProduct(key, f.internalName(), at));
    }
    return catalog.insertBaseProduct(null, f.internalName(), at);
  }

  private void reject(
      DraftRow d, String approvalId, String code, @Nullable String detail, Instant at) {
    List<DraftProblem> problems = new ArrayList<>(draftService.problems(d));
    problems.add(new DraftProblem(code, null, detail));
    drafts.markRejected(d.draftId(), approvalId, mapper.writeValueAsString(problems), at);
  }

  private void settleBatch(String batchId) {
    List<DraftRow> all = drafts.ofBatch(batchId);
    long applied = all.stream().filter(x -> "APPLIED".equals(x.state())).count();
    long open = all.stream().filter(x -> "SUBMITTED".equals(x.state())).count();
    if (open > 0) {
      return;
    }
    drafts.updateBatchState(
        batchId,
        applied == 0 ? "REJECTED" : applied == all.size() ? "APPLIED" : "PARTIALLY_APPLIED");
  }

  private static @Nullable Integer intOrNull(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asInt() : null;
  }
}
