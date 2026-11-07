package com.pmart.product.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.product.domain.CatalogLookup;
import com.pmart.product.domain.DraftFacts;
import com.pmart.product.domain.DraftProblem;
import com.pmart.product.domain.DraftRules;
import com.pmart.product.domain.Hashes;
import com.pmart.product.infrastructure.CatalogStore;
import com.pmart.product.infrastructure.CategoryStore;
import com.pmart.product.infrastructure.DraftStore;
import com.pmart.product.infrastructure.DraftStore.BatchRow;
import com.pmart.product.infrastructure.DraftStore.DraftRow;
import com.pmart.product.infrastructure.SeedBundleReader;
import com.pmart.product.infrastructure.SeedBundleReader.SeedProduct;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 상품 등록 초안(개별·seed-v1 배치)과 제출 준비. 초안은 승인 전까지 정본이 아니며, 승인 반영은 {@link ApprovalApplier} 가 한다. 원천
 * 키(sourceSystem, sourceKey) 가 있으면 그 키의 열린 초안을 갱신(upsert)해 다시 올려도 상품 수가 늘지 않는다.
 */
@Service
public class ProductDraftService {

  public static final String DRAFT_PREFIX = "productDraft:";
  public static final String BATCH_PREFIX = "productImportBatch:";

  /** 제출 준비 결과(계약 SubmissionResult). */
  public record Submission(
      List<String> submittedDraftIds,
      List<String> excludedDraftIds,
      Map<String, Object> approvalRequest) {}

  private final DraftStore drafts;
  private final CatalogStore catalog;
  private final CategoryStore categories;
  private final SeedBundleReader bundles;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ProductDraftService(
      DraftStore drafts,
      CatalogStore catalog,
      CategoryStore categories,
      SeedBundleReader bundles,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      Clock clock) {
    this.drafts = drafts;
    this.catalog = catalog;
    this.categories = categories;
    this.bundles = bundles;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
  }

  // ---------- 개별 초안 ----------

  public Outcome<DraftRow> create(String requestId, Operator op, JsonNode input) {
    return idempotency.execute(
        op.key(requestId, "createProductDraft"),
        input,
        DraftRow.class,
        () -> upsert(op, input, null));
  }

  public Outcome<DraftRow> update(
      String requestId,
      Operator op,
      String draftId,
      @Nullable Integer expectedVersion,
      JsonNode input) {
    return idempotency.execute(
        op.key(requestId, "updateProductDraft"),
        Map.of("draftId", draftId, "input", input),
        DraftRow.class,
        () -> {
          DraftRow d = get(draftId);
          if (expectedVersion != null && expectedVersion != d.version()) {
            throw PmartException.versionConflict(
                "초안이 이미 바뀌었습니다(기대 " + expectedVersion + ", 현재 " + d.version() + ").");
          }
          requireEditable(d);
          Validated v = validate(input, d.sourceSystem(), d.sourceKey());
          drafts.replaceInput(
              draftId,
              d.version(),
              null,
              v.state(),
              v.problemsJson(),
              json(input),
              v.categoriesJson(),
              now());
          return get(draftId);
        });
  }

  public DraftRow get(String draftId) {
    return drafts
        .find(draftId)
        .orElseThrow(() -> PmartException.notFound("DRAFT_NOT_FOUND", "초안이 없습니다: " + draftId));
  }

  public Outcome<Submission> submit(String requestId, Operator op, String draftId) {
    return idempotency.execute(
        op.key(requestId, "submitProductDraft"),
        Map.of("draftId", draftId),
        Submission.class,
        () -> {
          DraftRow d = get(draftId);
          if ("SUBMITTED".equals(d.state())) {
            return submission(DRAFT_PREFIX + draftId, List.of(d), List.of(), "상품 등록 1건");
          }
          requireEditable(d);
          if (!"VALID".equals(d.validationState())) {
            throw new PmartException(
                "DRAFT_INVALID",
                ErrorCode.DOMAIN_REJECTED.status(),
                "검증을 통과하지 않은 초안은 제출할 수 없습니다.",
                false,
                List.of());
          }
          lock(d);
          return submission(
              DRAFT_PREFIX + draftId, List.of(get(draftId)), List.of(), "상품 등록 1건: " + name(d));
        });
  }

  // ---------- 배치(seed-v1) ----------

  public Outcome<BatchRow> uploadBatch(
      String requestId, Operator op, byte[] zip, @Nullable String fileName) {
    return uploadBatch(requestId, op, UploadedBundle.of(zip), fileName);
  }

  public Outcome<BatchRow> uploadBatch(
      String requestId, Operator op, UploadedBundle zip, @Nullable String fileName) {
    return idempotency.execute(
        op.key(requestId, "uploadProductImportBatch"),
        Map.of("sha256", zip.sha256()),
        BatchRow.class,
        () -> {
          if (categories.effective().isEmpty()) {
            throw new PmartException(
                "CATEGORY_TREE_NOT_EFFECTIVE",
                ErrorCode.DOMAIN_REJECTED.status(),
                "발효된 카테고리 트리가 없습니다. 트리를 먼저 승인하세요.",
                false,
                List.of());
          }
          List<SeedProduct> products;
          try (java.io.InputStream in = zip.open()) {
            products = bundles.read(in);
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
          String batchId = "IB-" + UUID.randomUUID();
          Instant at = now();
          drafts.insert(
              new BatchRow(
                  batchId,
                  "SEED_V1",
                  SeedBundleReader.DEFAULT_SOURCE_SYSTEM,
                  fileName,
                  "VALIDATED",
                  0,
                  0,
                  0,
                  op.id(),
                  at));
          int valid = 0;
          int invalid = 0;
          for (SeedProduct p : products) {
            DraftRow d = upsert(op, seedToInput(p), batchId);
            if ("VALID".equals(d.validationState())) {
              valid++;
            } else {
              invalid++;
            }
          }
          drafts.updateBatchCounts(batchId, products.size(), valid, invalid);
          return drafts.findBatch(batchId).orElseThrow();
        });
  }

  public BatchRow batch(String batchId) {
    return drafts
        .findBatch(batchId)
        .orElseThrow(() -> PmartException.notFound("BATCH_NOT_FOUND", "배치가 없습니다: " + batchId));
  }

  public List<DraftRow> batchDrafts(String batchId) {
    return drafts.ofBatch(batchId);
  }

  public Outcome<Submission> submitBatch(String requestId, Operator op, String batchId) {
    return idempotency.execute(
        op.key(requestId, "submitProductImportBatch"),
        Map.of("batchId", batchId),
        Submission.class,
        () -> {
          batch(batchId);
          List<DraftRow> submitted = new ArrayList<>();
          List<String> excluded = new ArrayList<>();
          for (DraftRow d : drafts.ofBatch(batchId)) {
            if ("DRAFT".equals(d.state()) && "VALID".equals(d.validationState())) {
              lock(d);
              submitted.add(get(d.draftId()));
            } else if ("SUBMITTED".equals(d.state())) {
              submitted.add(d);
            } else {
              excluded.add(d.draftId());
            }
          }
          if (submitted.isEmpty()) {
            throw new PmartException(
                "NOTHING_TO_SUBMIT",
                ErrorCode.DOMAIN_REJECTED.status(),
                "제출할 수 있는(VALID) 초안이 없습니다.",
                false,
                List.of());
          }
          drafts.updateBatchState(batchId, "SUBMITTED");
          return submission(
              BATCH_PREFIX + batchId, submitted, excluded, batchSummary(submitted.size(), batchId));
        });
  }

  /** 승인 대기 중인 배치의 제출 결과(BR-41): 잠긴(SUBMITTED) 초안으로 제출 응답과 같은 본문을 다시 만든다. 승인 대기가 아니면 비어 있다. */
  public Optional<Submission> pendingSubmission(String batchId) {
    if (!"SUBMITTED".equals(batch(batchId).state())) {
      return Optional.empty();
    }
    List<DraftRow> submitted = new ArrayList<>();
    List<String> excluded = new ArrayList<>();
    for (DraftRow d : drafts.ofBatch(batchId)) {
      if ("SUBMITTED".equals(d.state())) {
        submitted.add(d);
      } else {
        excluded.add(d.draftId());
      }
    }
    return submitted.isEmpty()
        ? Optional.empty()
        : Optional.of(
            submission(
                BATCH_PREFIX + batchId,
                submitted,
                excluded,
                batchSummary(submitted.size(), batchId)));
  }

  private static String batchSummary(int count, String batchId) {
    return "상품 일괄 등록 " + count + "건 (배치 " + batchId + ")";
  }

  /**
   * id-map.json 항목: seedProductId(·identityKey) → productId·sku. identityKey 는 묶음의
   * product.baseProductKey(seed-v1 에서 identityKey 와 같은 값)다.
   */
  public List<Map<String, Object>> idMap(String batchId) {
    batch(batchId);
    List<Map<String, Object>> out = new ArrayList<>();
    for (DraftRow d : drafts.ofBatch(batchId)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("seedProductId", d.sourceKey());
      JsonNode key = mapper.readTree(d.input()).path("product").path("baseProductKey");
      m.put("identityKey", key.isString() ? key.asString() : null);
      m.put("pmartProductId", d.resultProductId());
      JsonNode skus = mapper.readTree(d.resultSkus());
      m.put("sku", skus.isArray() && !skus.isEmpty() ? skus.get(0).asString() : null);
      m.put("state", d.state());
      out.add(m);
    }
    return out;
  }

  // ---------- 내부 ----------

  private DraftRow upsert(Operator op, JsonNode input, @Nullable String batchId) {
    String system = text(input, "sourceSystem");
    String key = text(input, "sourceKey");
    Validated v = validate(input, system, key);
    Instant at = now();
    if (system != null && key != null) {
      Optional<DraftRow> open = drafts.findOpen(system, key);
      if (open.isPresent()) {
        DraftRow d = open.get();
        if ("SUBMITTED".equals(d.state())) {
          return d; // 심사 중이면 바꾸지 않는다(승인 범위 고정)
        }
        drafts.replaceInput(
            d.draftId(),
            d.version(),
            batchId,
            v.state(),
            v.problemsJson(),
            json(input),
            v.categoriesJson(),
            at);
        return get(d.draftId());
      }
    }
    DraftRow row =
        new DraftRow(
            "PD-" + UUID.randomUUID(),
            batchId,
            system,
            key,
            "DRAFT",
            v.state(),
            v.problemsJson(),
            json(input),
            v.categoriesJson(),
            null,
            null,
            null,
            "[]",
            1,
            op.id(),
            at,
            at);
    drafts.insert(row);
    return row;
  }

  private record Validated(String state, String problemsJson, String categoriesJson) {}

  private Validated validate(JsonNode input, @Nullable String system, @Nullable String key) {
    Set<String> ownSkus = new HashSet<>();
    List<DraftProblem> targetProblems = new ArrayList<>();
    String targetId = text(input, "targetProductId");
    Optional<CatalogStore.SourceLinkRow> link =
        system != null && key != null ? catalog.sourceLink(system, key) : Optional.empty();
    Optional<CatalogStore.SourceLinkRow> target = updateTarget(input, system, key);
    if (targetId != null && target.isEmpty()) {
      targetProblems.add(
          DraftProblem.of(
              "TARGET_PRODUCT_NOT_FOUND", "targetProductId", "갱신할 상품이 없습니다: " + targetId));
    }
    if (targetId != null && link.isPresent() && !link.get().productId().equals(targetId)) {
      targetProblems.add(
          DraftProblem.of(
              "TARGET_CONFLICT",
              "targetProductId",
              "원천 키 " + key + " 는 다른 상품 " + link.get().productId() + " 에 연결돼 있습니다."));
    }
    target.ifPresent(l -> mapper.readTree(l.skus()).forEach(s -> ownSkus.add(s.asString())));
    DraftRules.Result r = DraftRules.validate(facts(input), lookup(), ownSkus);
    List<DraftProblem> problems = new ArrayList<>(targetProblems);
    problems.addAll(r.problems());
    return new Validated(
        problems.isEmpty() ? "VALID" : "INVALID",
        mapper.writeValueAsString(problems),
        mapper.writeValueAsString(r.categoryIds()));
  }

  /**
   * 승인 반영 때 갱신할 기존 상품. {@code targetProductId} 가 있으면 그 상품(원천 키 없이 등록된 상품의 속성 변경, 2026-11-07), 없으면
   * 원천 키 연결(BR-39). 둘 다 없으면 새 상품.
   */
  public Optional<CatalogStore.SourceLinkRow> updateTarget(
      JsonNode input, @Nullable String system, @Nullable String key) {
    String targetId = text(input, "targetProductId");
    if (targetId != null) {
      return catalog
          .findProduct(targetId)
          .map(
              p ->
                  new CatalogStore.SourceLinkRow(
                      system,
                      key,
                      p.productId(),
                      mapper.writeValueAsString(
                          catalog.skusOf(p.productId()).stream()
                              .map(CatalogStore.SkuRow::sku)
                              .toList()),
                      null));
    }
    if (system == null || key == null) {
      return Optional.empty();
    }
    return catalog.sourceLink(system, key);
  }

  /** 발효 트리 기준 조회. 발효 트리가 없으면 카테고리는 모두 없음으로 본다. */
  CatalogLookup lookup() {
    Integer version = categories.effective().map(CategoryStore.VersionRow::version).orElse(null);
    return new CatalogLookup() {
      @Override
      public boolean categoryExists(String categoryId) {
        return version != null && categories.nodeExists(version, categoryId);
      }

      @Override
      public Optional<String> categoryByPath(String path) {
        return version == null
            ? Optional.empty()
            : categories.nodeByPath(version, DraftRules.normalizePath(path));
      }

      @Override
      public boolean filterOptionExists(String filterGroupId, String option) {
        return version != null && categories.optionExists(version, filterGroupId, option);
      }

      @Override
      public Optional<String> activeBarcodeOwner(String barcode) {
        return catalog.activeBarcodeOwner(barcode);
      }

      @Override
      public boolean baseProductExists(String baseProductId) {
        return catalog.baseProductExists(baseProductId);
      }
    };
  }

  /** 계약 ProductDraftInput(JSON) → 검증 입력. */
  DraftFacts facts(JsonNode in) {
    JsonNode p = in.path("product");
    JsonNode s = in.path("skus").path(0);
    List<String> barcodes = new ArrayList<>();
    s.path("barcodes").forEach(b -> barcodes.add(b.asString()));
    List<String> ids = new ArrayList<>();
    p.path("categoryIds").forEach(c -> ids.add(c.asString()));
    List<String> paths = new ArrayList<>();
    p.path("categoryPaths").forEach(c -> paths.add(c.asString()));
    List<DraftFacts.FilterOptionRef> options = new ArrayList<>();
    in.path("filterOptions")
        .forEach(
            o ->
                options.add(
                    new DraftFacts.FilterOptionRef(
                        o.path("filterGroupId").asString(), o.path("option").asString())));
    return new DraftFacts(
        text(p, "internalName"),
        text(p, "baseProductId"),
        text(s, "unitType"),
        s.hasNonNull("unitsPerSku") ? s.get("unitsPerSku").asInt() : null,
        s.hasNonNull("weightG") ? s.get("weightG").asInt() : null,
        text(s, "storageType"),
        intOrNull(s.path("shelfLifeGuarantee"), "minRemainingDays"),
        intOrNull(s.path("shelfLifeGuarantee"), "maxDaysSinceManufacture"),
        barcodes,
        text(p, "primaryCategoryId"),
        ids,
        paths,
        options);
  }

  /**
   * seed-v1 product.json → ProductDraftInput. B마트 경로(구간 배열)는 " > " 로 이어 이름 경로로 둔다(자동 매핑). sku 는 항상
   * 비어 있고(승인 후 발급), 이름에서 읽은 추정값은 derivedFields 로 표시한다. 주류 여부가 비어 있으면 false 로 두고 추정값으로 표시한다.
   */
  ObjectNode seedToInput(SeedProduct p) {
    JsonNode root = p.json();
    JsonNode prod = root.path("product");
    JsonNode sku = root.path("skus").path(0);
    ObjectNode in = mapper.createObjectNode();
    in.put(
        "sourceSystem",
        root.path("source").path("system").asString(SeedBundleReader.DEFAULT_SOURCE_SYSTEM));
    in.put("sourceKey", p.seedProductId());
    ObjectNode product = in.putObject("product");
    copyText(prod, product, "baseProductKey");
    copyText(prod, product, "internalName");
    copyText(prod, product, "saleState");
    // 묶음이 준 P마트 카테고리 id(B마트 트리 id 와 같음)가 있으면 그것으로 연결하고, 이름 경로는 id 가 없을 때만 쓰인다(DraftRules).
    copyText(prod, product, "primaryCategoryId");
    ArrayNode ids = mapper.createArrayNode();
    prod.path("categoryIds")
        .forEach(
            c -> {
              if (c.isString() && !c.asString().isBlank()) {
                ids.add(c.asString());
              }
            });
    if (!ids.isEmpty()) {
      product.set("categoryIds", ids);
    }
    ArrayNode paths = product.putArray("categoryPaths");
    if (prod.path("primaryCategory").isArray()) {
      paths.add(joinPath(prod.get("primaryCategory")));
    }
    for (JsonNode c : prod.path("categories")) {
      String path = c.isArray() ? joinPath(c) : c.asString();
      boolean dup = false;
      for (JsonNode existing : paths) {
        dup |= existing.asString().equals(path);
      }
      if (!dup && !path.isBlank()) {
        paths.add(path);
      }
    }
    ObjectNode s = in.putArray("skus").addObject();
    copyText(sku, s, "unitType");
    copyInt(sku, s, "unitsPerSku");
    copyInt(sku, s, "weightG");
    copyInt(sku, s, "volumeMl");
    copyText(sku, s, "storageType");
    if (sku.path("shelfLifeGuarantee").isObject()) {
      s.set("shelfLifeGuarantee", sku.get("shelfLifeGuarantee").deepCopy());
    }
    ArrayNode derived = in.putArray("derivedFields");
    root.path("source").path("derived").forEach(derived::add);
    if (sku.hasNonNull("alcohol")) {
      s.put("alcohol", sku.get("alcohol").asBoolean());
    } else {
      s.put("alcohol", false);
      derived.add("alcohol");
    }
    ArrayNode barcodes = s.putArray("barcodes");
    sku.path("barcodes").forEach(barcodes::add);
    return in;
  }

  private void lock(DraftRow d) {
    String hash = Hashes.sha256(d.input(), d.resolvedCategories());
    if (!drafts.lock(d.draftId(), hash, now())) {
      throw PmartException.versionConflict("초안이 이미 바뀌었습니다. 다시 조회하세요.");
    }
  }

  private Submission submission(
      String targetRef, List<DraftRow> locked, List<String> excluded, String summary) {
    List<Map<String, Object>> lines = new ArrayList<>();
    for (DraftRow d : locked) {
      Map<String, Object> attrs = new LinkedHashMap<>();
      attrs.put("draftId", d.draftId());
      attrs.put("draftHash", d.draftHash());
      if (d.sourceKey() != null) {
        attrs.put("sourceKey", d.sourceKey());
      }
      attrs.put("internalName", name(d));
      // 승인자가 새 등록인지 기존 상품 갱신인지 알 수 있게(BR-39)
      Optional<CatalogStore.SourceLinkRow> existing = existingProduct(d);
      attrs.put("registration", existing.isPresent() ? "UPDATE" : "NEW");
      existing.ifPresent(
          l -> {
            attrs.put("existingProductId", l.productId());
            attrs.put("existingSkus", mapper.readTree(l.skus()));
          });
      lines.add(
          Map.of(
              "lineKey",
              d.draftId(),
              "targetRef",
              DRAFT_PREFIX + d.draftId(),
              "attributes",
              attrs));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "PRODUCT");
    body.put("targetService", "product-service");
    body.put("targetRef", targetRef);
    body.put("allowedActions", List.of("ISSUE_SKU", "PUBLISH_PRODUCT_VERSION"));
    body.put("summary", summary);
    body.put("lines", lines);
    return new Submission(locked.stream().map(DraftRow::draftId).toList(), excluded, body);
  }

  private static void requireEditable(DraftRow d) {
    if (!"DRAFT".equals(d.state())) {
      throw new PmartException(
          "DRAFT_NOT_EDITABLE",
          ErrorCode.DOMAIN_REJECTED.status(),
          "제출·반영된 초안은 바꿀 수 없습니다(상태 " + d.state() + ").",
          false,
          List.of());
    }
  }

  private String name(DraftRow d) {
    return mapper.readTree(d.input()).path("product").path("internalName").asString("");
  }

  private String json(JsonNode n) {
    return mapper.writeValueAsString(n);
  }

  private static String joinPath(JsonNode segments) {
    List<String> parts = new ArrayList<>();
    segments.forEach(x -> parts.add(x.asString()));
    return DraftRules.normalizePath(String.join(" > ", parts));
  }

  private static void copyText(JsonNode from, ObjectNode to, String field) {
    if (from.hasNonNull(field)) {
      to.put(field, from.get(field).asString());
    }
  }

  private static void copyInt(JsonNode from, ObjectNode to, String field) {
    if (from.hasNonNull(field)) {
      to.put(field, from.get(field).asInt());
    }
  }

  private static @Nullable Integer intOrNull(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asInt() : null;
  }

  private static @Nullable String text(JsonNode n, String field) {
    return n.hasNonNull(field) && !n.get(field).asString().isBlank()
        ? n.get(field).asString()
        : null;
  }

  private Instant now() {
    return clock.instant();
  }

  /** 문제 목록(JSON) → 객체. */
  /**
   * 심사 전 초안이 기존 상품을 갱신하는지(BR-39): 원천 키가 이미 상품으로 등록돼 있으면 그 연결. 승인 반영(ApprovalApplier)이 같은 연결을 찾아 새
   * 상품 대신 그 상품의 새 버전을 만든다. 반영·반려된 초안은 비어 있다(결과는 resultProductId).
   */
  public Optional<CatalogStore.SourceLinkRow> existingProduct(DraftRow d) {
    if (!("DRAFT".equals(d.state()) || "SUBMITTED".equals(d.state()))) {
      return Optional.empty();
    }
    return updateTarget(mapper.readTree(d.input()), d.sourceSystem(), d.sourceKey());
  }

  public List<DraftProblem> problems(DraftRow d) {
    return List.of(mapper.readValue(d.problems(), DraftProblem[].class));
  }
}
