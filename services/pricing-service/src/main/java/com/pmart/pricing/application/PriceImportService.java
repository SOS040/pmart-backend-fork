package com.pmart.pricing.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.pricing.domain.PriceImportRules;
import com.pmart.pricing.domain.PriceImportRules.Classified;
import com.pmart.pricing.domain.PriceImportRules.LineFacts;
import com.pmart.pricing.infrastructure.PriceImportStore;
import com.pmart.pricing.infrastructure.PriceImportStore.BatchRow;
import com.pmart.pricing.infrastructure.PriceImportStore.LineRow;
import com.pmart.pricing.infrastructure.PriceStore;
import com.pmart.pricing.infrastructure.PriceStore.ApprovalLineRow;
import com.pmart.pricing.infrastructure.ProductLinks;
import com.pmart.pricing.infrastructure.ProductLinks.Link;
import com.pmart.pricing.infrastructure.SeedPriceReader;
import com.pmart.pricing.infrastructure.SeedPriceReader.SeedPrice;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 가격 일괄 등록(사용자 결정 2026-11-03): seed-v1 묶음의 basePrice 를 SKU 별로 미리 채우고, 운영자가 행마다 고치거나 빼고, 공통 효력 시각을
 * 정해 PRICE 승인 요청 한 건으로 제출한다. 대상은 가격이 없던 SKU(NEW)와 현재 가격과 다른 SKU(CHANGE), 같은 가격은 뺀다.
 *
 * <p>가격 버전은 여기서 만들지 않는다 — 승인 요청 본문만 돌려주고, 승인되면 기존 {@link PriceApprovalApplier} 가 반영한다.
 * product-service 조회(원천 키 → SKU)는 트랜잭션 밖에서 먼저 하고, 저장은 멱등 트랜잭션 안에서 한다.
 */
@Service
public class PriceImportService {

  public static final String TARGET_PREFIX = "priceImportBatch:";

  /** 제출 결과(멱등 재생용으로 저장된다). */
  public record Submitted(String batchId, Map<String, Object> approvalRequest) {}

  private final PriceImportStore store;
  private final PriceStore prices;
  private final PriceQueries queries;
  private final ProductLinks links;
  private final SeedPriceReader reader;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public PriceImportService(
      PriceImportStore store,
      PriceStore prices,
      PriceQueries queries,
      ProductLinks links,
      SeedPriceReader reader,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.prices = prices;
    this.queries = queries;
    this.links = links;
    this.reader = reader;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  // ---------- 명령 ----------

  public String upload(
      String requestId, String sourceSystem, byte[] zip, @Nullable String fileName) {
    PmartPrincipal op = operator();
    List<SeedPrice> seeds = reader.read(new ByteArrayInputStream(zip));
    String system = seeds.getFirst().sourceSystem();
    Map<String, Link> found =
        links.lookup(system, seeds.stream().map(SeedPrice::seedProductId).toList());
    Map<String, Long> current = currentPrices(found.values().stream().map(Link::sku).toList());
    Instant at = clock.instant();
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "uploadPriceImportBatch", op.subject()),
            Map.of("sha256", sha256(zip)),
            String.class,
            () -> {
              String batchId = "PB-" + UUID.randomUUID();
              store.insertBatch(
                  new BatchRow(
                      batchId,
                      "SEED_V1",
                      system,
                      fileName,
                      "DRAFT",
                      1,
                      null,
                      null,
                      null,
                      at,
                      op.subject(),
                      at,
                      null));
              List<LineRow> lines = new ArrayList<>();
              int no = 0;
              for (SeedPrice s : seeds) {
                Link l = found.get(s.seedProductId());
                String sku = l == null ? null : l.sku();
                lines.add(
                    new LineRow(
                        batchId,
                        s.seedProductId(),
                        ++no,
                        s.productName(),
                        l == null ? null : l.productId(),
                        sku,
                        s.basePrice(),
                        s.basePrice(),
                        false,
                        false,
                        sku == null ? null : current.get(sku),
                        null,
                        "READY"));
              }
              classify(lines).forEach(store::insertLine);
              return batchId;
            })
        .value();
  }

  /** 행 설정: 가격 고치기(null = 묶음 값으로 되돌림)·빼기. */
  public void updateLine(
      String requestId,
      String sourceSystem,
      String batchId,
      String seedProductId,
      @Nullable Long basePrice,
      boolean excluded,
      @Nullable Integer expectedVersion) {
    PmartPrincipal op = operator();
    if (basePrice != null && basePrice <= 0) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "가격은 1원 이상이어야 합니다(0원 허용은 미정).");
    }
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("batchId", batchId);
    request.put("seedProductId", seedProductId);
    request.put("basePrice", basePrice);
    request.put("excluded", excluded);
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "updatePriceImportLine", op.subject()),
        request,
        String.class,
        () -> {
          lockDraft(batchId, expectedVersion);
          List<LineRow> lines = new ArrayList<>(store.lines(batchId));
          int i = indexOf(lines, seedProductId);
          LineRow l = lines.get(i);
          Long price = basePrice == null ? l.bundlePrice() : basePrice;
          lines.set(
              i,
              new LineRow(
                  l.batchId(),
                  l.seedProductId(),
                  l.lineNo(),
                  l.productName(),
                  l.productId(),
                  l.sku(),
                  l.bundlePrice(),
                  price,
                  basePrice != null && !Objects.equals(basePrice, l.bundlePrice()),
                  excluded,
                  l.currentPrice(),
                  l.changeType(),
                  l.status()));
          saveChanged(lines);
          store.touch(batchId, null);
          return batchId;
        });
  }

  /** SKU·현재 가격 다시 대조(운영자 설정은 유지). */
  public void refresh(String requestId, String sourceSystem, String batchId) {
    PmartPrincipal op = operator();
    BatchRow b = batch(batchId);
    Resolution r = resolve(b, store.lines(batchId));
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "refreshPriceImportBatch", op.subject()),
        Map.of("batchId", batchId),
        String.class,
        () -> {
          lockDraft(batchId, null);
          saveChanged(apply(store.lines(batchId), r));
          store.touch(batchId, r.at());
          return batchId;
        });
  }

  /** 제출: 다시 대조하고 READY 행을 잠가 PRICE 승인 요청 본문을 만든다. */
  public Submitted submit(
      String requestId,
      String sourceSystem,
      String batchId,
      @Nullable Instant effectiveFrom,
      @Nullable Integer expectedVersion) {
    PmartPrincipal op = operator();
    if (effectiveFrom != null && effectiveFrom.isBefore(clock.instant())) {
      throw new PmartException(
          "EFFECTIVE_FROM_IN_PAST",
          ErrorCode.DOMAIN_REJECTED.status(),
          "효력 시각이 지났습니다. 비우면 승인 반영 즉시 효력입니다.",
          false,
          List.of());
    }
    BatchRow b = batch(batchId);
    Resolution r = resolve(b, store.lines(batchId));
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("batchId", batchId);
    request.put("effectiveFrom", effectiveFrom == null ? null : effectiveFrom.toString());
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "submitPriceImportBatch", op.subject()),
            request,
            Submitted.class,
            () -> {
              lockDraft(batchId, expectedVersion);
              List<LineRow> lines = apply(store.lines(batchId), r);
              saveChanged(lines);
              List<LineRow> ready = lines.stream().filter(l -> "READY".equals(l.status())).toList();
              if (ready.isEmpty()) {
                throw new PmartException(
                    "NOTHING_TO_SUBMIT",
                    ErrorCode.DOMAIN_REJECTED.status(),
                    "제출할 행이 없습니다(새 가격·가격 변경 행 없음).",
                    false,
                    List.of());
              }
              String targetRef = TARGET_PREFIX + batchId;
              store.touch(batchId, r.at());
              store.markSubmitted(batchId, effectiveFrom, targetRef, clock.instant());
              return new Submitted(batchId, approvalRequest(targetRef, ready, effectiveFrom));
            })
        .value();
  }

  /**
   * ApprovalDecided(PRICE) 반영 뒤 호출된다(같은 inbox 트랜잭션). targetRef 가 이 배치면 승인 결과를 연결한다. 반영 결과 APPLIED →
   * APPLIED, PARTIAL → PARTIALLY_APPLIED, REJECTED → REJECTED.
   */
  public void onApprovalApplied(@Nullable String targetId, String approvalId, String result) {
    if (targetId == null || !targetId.startsWith(TARGET_PREFIX)) {
      return;
    }
    String state =
        switch (result) {
          case "APPLIED" -> "APPLIED";
          case "PARTIAL" -> "PARTIALLY_APPLIED";
          default -> "REJECTED";
        };
    store.markDecided(targetId.substring(TARGET_PREFIX.length()), approvalId, state);
  }

  // ---------- 조회 ----------

  public BatchRow batch(String batchId) {
    return store
        .batch(batchId, false)
        .orElseThrow(() -> PmartException.notFound("BATCH_NOT_FOUND", "배치가 없습니다: " + batchId));
  }

  /** 계약 PriceImportBatch 모양. */
  public Map<String, Object> view(String batchId) {
    BatchRow b = batch(batchId);
    List<LineRow> lines = store.lines(batchId);
    Map<String, ApprovalLineRow> results =
        b.approvalId() == null
            ? Map.of()
            : prices.approvalLines(b.approvalId()).stream()
                .collect(Collectors.toMap(ApprovalLineRow::sku, Function.identity(), (x, y) -> x));
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("batchId", b.batchId());
    m.put("format", b.format());
    m.put("sourceSystem", b.sourceSystem());
    m.put("sourceName", b.sourceName());
    m.put("state", b.state());
    m.put("version", b.version());
    m.put("lineCount", lines.size());
    m.put(
        "submittableCount",
        "DRAFT".equals(b.state())
            ? lines.stream().filter(l -> "READY".equals(l.status())).count()
            : 0);
    m.put("effectiveFrom", str(b.effectiveFrom()));
    m.put("approvalTargetRef", b.approvalTargetRef());
    m.put("approvalId", b.approvalId());
    m.put("checkedAt", b.checkedAt().toString());
    List<Map<String, Object>> out = new ArrayList<>();
    for (LineRow l : lines) {
      Map<String, Object> x = new LinkedHashMap<>();
      x.put("seedProductId", l.seedProductId());
      x.put("productName", l.productName());
      x.put("productId", l.productId());
      x.put("sku", l.sku());
      x.put("bundlePrice", l.bundlePrice());
      x.put("basePrice", l.basePrice());
      x.put("currentPrice", l.currentPrice());
      if (l.changeType() != null) {
        x.put("changeType", l.changeType());
      }
      x.put("status", l.status());
      x.put("excluded", l.excluded());
      x.put("edited", l.edited());
      ApprovalLineRow res = l.sku() == null ? null : results.get(l.sku());
      if (res != null && "READY".equals(l.status())) {
        Map<String, Object> rr = new LinkedHashMap<>();
        rr.put("state", res.state());
        rr.put("reasonCode", res.reasonCode());
        rr.put("priceVersion", res.priceVersion());
        x.put("result", rr);
      }
      out.add(x);
    }
    m.put("lines", out);
    m.put("createdAt", b.createdAt().toString());
    m.put("submittedAt", str(b.submittedAt()));
    return m;
  }

  // ---------- 내부 ----------

  private record Resolution(Map<String, Link> links, Map<String, Long> current, Instant at) {}

  private Resolution resolve(BatchRow b, List<LineRow> lines) {
    Map<String, Link> found =
        links.lookup(b.sourceSystem(), lines.stream().map(LineRow::seedProductId).toList());
    return new Resolution(
        found, currentPrices(found.values().stream().map(Link::sku).toList()), clock.instant());
  }

  private static List<LineRow> apply(List<LineRow> lines, Resolution r) {
    List<LineRow> out = new ArrayList<>();
    for (LineRow l : lines) {
      Link link = r.links().get(l.seedProductId());
      String sku = link == null ? null : link.sku();
      out.add(
          new LineRow(
              l.batchId(),
              l.seedProductId(),
              l.lineNo(),
              l.productName(),
              link == null ? null : link.productId(),
              sku,
              l.bundlePrice(),
              l.basePrice(),
              l.edited(),
              l.excluded(),
              sku == null ? null : r.current().get(sku),
              l.changeType(),
              l.status()));
    }
    return out;
  }

  /** 다시 분류해 바뀐 행만 저장한다. */
  private void saveChanged(List<LineRow> lines) {
    List<LineRow> classified = classify(lines);
    Map<String, LineRow> before =
        store.lines(lines.getFirst().batchId()).stream()
            .collect(Collectors.toMap(LineRow::seedProductId, Function.identity()));
    for (LineRow l : classified) {
      if (!l.equals(before.get(l.seedProductId()))) {
        store.updateLine(l);
      }
    }
  }

  private static List<LineRow> classify(List<LineRow> lines) {
    List<Classified> c =
        PriceImportRules.classifyAll(
            lines.stream()
                .map(l -> new LineFacts(l.sku(), l.basePrice(), l.currentPrice(), l.excluded()))
                .toList());
    List<LineRow> out = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      LineRow l = lines.get(i);
      Classified k = c.get(i);
      out.add(
          new LineRow(
              l.batchId(),
              l.seedProductId(),
              l.lineNo(),
              l.productName(),
              l.productId(),
              l.sku(),
              l.bundlePrice(),
              l.basePrice(),
              l.edited(),
              l.excluded(),
              l.currentPrice(),
              k.changeType() == null ? null : k.changeType().name(),
              k.status().name()));
    }
    return out;
  }

  private Map<String, Long> currentPrices(List<String> skus) {
    Map<String, Long> out = new HashMap<>();
    if (skus.isEmpty()) {
      return out;
    }
    queries
        .prices(skus, null)
        .items()
        .forEach(p -> out.put(p.current().sku(), p.current().basePrice()));
    return out;
  }

  private void lockDraft(String batchId, @Nullable Integer expectedVersion) {
    BatchRow b =
        store
            .batch(batchId, true)
            .orElseThrow(() -> PmartException.notFound("BATCH_NOT_FOUND", "배치가 없습니다: " + batchId));
    if (!"DRAFT".equals(b.state())) {
      throw new PmartException(
          "BATCH_LOCKED", HttpStatus.CONFLICT, "이미 제출된 배치입니다: " + b.state(), false, List.of());
    }
    if (expectedVersion != null && expectedVersion != b.version()) {
      throw PmartException.versionConflict("배치가 바뀌었습니다(현재 버전 " + b.version() + "). 다시 읽고 고치세요.");
    }
  }

  private static int indexOf(List<LineRow> lines, String seedProductId) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).seedProductId().equals(seedProductId)) {
        return i;
      }
    }
    throw PmartException.notFound("LINE_NOT_FOUND", "배치에 없는 상품입니다: " + seedProductId);
  }

  private static Map<String, Object> approvalRequest(
      String targetRef, List<LineRow> ready, @Nullable Instant effectiveFrom) {
    long newCount = ready.stream().filter(l -> "NEW".equals(l.changeType())).count();
    List<Map<String, Object>> lines = new ArrayList<>();
    for (LineRow l : ready) {
      Map<String, Object> attrs = new LinkedHashMap<>();
      attrs.put("sku", l.sku());
      attrs.put("basePrice", l.basePrice());
      if (effectiveFrom != null) {
        attrs.put("effectiveFrom", effectiveFrom.toString());
      }
      attrs.put("previousPrice", l.currentPrice());
      attrs.put("changeType", l.changeType());
      attrs.put("seedProductId", l.seedProductId());
      attrs.put("productName", l.productName());
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("lineKey", l.sku());
      line.put("requestedAmount", l.basePrice());
      line.put("attributes", attrs);
      lines.add(line);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "PRICE");
    body.put("targetService", "pricing-service");
    body.put("targetRef", targetRef);
    body.put("allowedActions", List.of("APPLY_PRICE_VERSION"));
    body.put(
        "summary",
        "가격 일괄 등록 "
            + ready.size()
            + "건 (새 가격 "
            + newCount
            + " · 가격 변경 "
            + (ready.size() - newCount)
            + ")");
    if (effectiveFrom != null) {
      body.put("requestedEffectiveFrom", effectiveFrom.toString());
    }
    body.put("lines", lines);
    return body;
  }

  private static PmartPrincipal operator() {
    return PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
  }

  private static @Nullable String str(@Nullable Instant i) {
    return i == null ? null : i.toString();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
