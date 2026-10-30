package com.pmart.search.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.search.domain.SearchRules;
import com.pmart.search.infrastructure.ContentCardClient;
import com.pmart.search.infrastructure.OpenSearchIndex;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 상품·콘텐츠·판매 상태·가격 사건 → 검색 색인. DB 가 없어 inbox 대신 <b>버전 비교 스크립트</b>로 멱등·순서 안전하게 갱신한다(같은 사건 재전달·늦게 온 옛
 * 사건은 바꾸지 않음). 실패는 예외로 올려 Kafka 재시도·DLT 에 맡긴다.
 *
 * <ul>
 *   <li>ProductVersionPublished: 상품 버전·카테고리(+조상 범위)·대표 SKU·판매 상태. 대표 SKU 의 가격이 이미 있으면 붙인다.
 *   <li>ProductContentPublished: 사건에 이름·이미지가 없어 공개 카드 API 로 보강(게시본만).
 *   <li>ProductSaleStateChanged: 시각이 같거나 늦은 것만.
 *   <li>PriceVersionEffective: SKU 가격 문서 → 그 SKU 를 대표로 둔 상품에 복사.
 * </ul>
 */
@Service
public class SearchIndexer {

  static final String PRODUCT_SCRIPT =
      """
      def s = ctx._source;
      if (s.productVersion == null || params.v > s.productVersion) {
        s.productId = params.productId;
        s.productVersion = params.v;
        s.categoryIds = params.categoryIds;
        s.categoryScope = params.scope;
        s.primaryCategoryId = params.primary;
        s.representativeSku = params.sku;
        if (s.saleStateAtMs == null || params.atMs >= s.saleStateAtMs) {
          s.saleState = params.saleState;
          s.saleStateAtMs = params.atMs;
        }
        s.updatedAtMs = params.now;
      } else {
        ctx.op = 'none';
      }
      """;

  static final String CONTENT_SCRIPT =
      """
      def s = ctx._source;
      if (s.contentVersion == null || params.cv > s.contentVersion) {
        s.productId = params.productId;
        s.contentVersion = params.cv;
        s.name = params.name;
        s.imageUrl = params.imageUrl;
        if (s.contentPublishedAtMs == null) {
          s.contentPublishedAtMs = params.atMs;
        }
        s.updatedAtMs = params.now;
      } else {
        ctx.op = 'none';
      }
      """;

  static final String SALE_STATE_SCRIPT =
      """
      def s = ctx._source;
      if (s.saleStateAtMs == null || params.atMs >= s.saleStateAtMs) {
        s.productId = params.productId;
        s.saleState = params.state;
        s.saleStateAtMs = params.atMs;
        s.updatedAtMs = params.now;
      } else {
        ctx.op = 'none';
      }
      """;

  static final String SKU_PRICE_SCRIPT =
      """
      def s = ctx._source;
      if (s.priceVersion == null || params.pv > s.priceVersion) {
        s.sku = params.sku;
        s.price = params.price;
        s.priceVersion = params.pv;
        s.effectiveAtMs = params.atMs;
      } else {
        ctx.op = 'none';
      }
      """;

  /** 상품 가격 갱신. 아무것도 안 할 때의 op 이름이 _update(none)와 _update_by_query(noop)에서 다르다. */
  private static final String PRODUCT_PRICE_TEMPLATE =
      """
      def s = ctx._source;
      if (s.priceVersion == null || params.pv > s.priceVersion) {
        s.basePrice = params.price;
        s.priceVersion = params.pv;
        s.updatedAtMs = params.now;
      } else {
        ctx.op = '%s';
      }
      """;

  static final String PRODUCT_PRICE_SCRIPT = PRODUCT_PRICE_TEMPLATE.formatted("none");
  static final String PRODUCT_PRICE_BY_QUERY_SCRIPT = PRODUCT_PRICE_TEMPLATE.formatted("noop");

  private static final Logger log = LoggerFactory.getLogger(SearchIndexer.class);

  private final OpenSearchIndex index;
  private final ContentCardClient cards;
  private final Clock clock;

  public SearchIndexer(OpenSearchIndex index, ContentCardClient cards, Clock clock) {
    this.index = index;
    this.cards = cards;
    this.clock = clock;
  }

  public void apply(EventEnvelope e) {
    switch (e.eventType()) {
      case "ProductVersionPublished" -> product(e);
      case "ProductContentPublished" -> content(e);
      case "ProductSaleStateChanged" -> saleState(e);
      case "PriceVersionEffective" -> price(e);
      default -> {
        // 이 서비스가 쓰지 않는 사건(SkuIssued·CategoryChanged 등)
      }
    }
  }

  void product(EventEnvelope e) {
    JsonNode p = e.payload();
    String productId = p.path("productId").asString();
    List<String> categoryIds = new ArrayList<>();
    p.path("categoryIds").forEach(c -> categoryIds.add(c.asString()));
    String sku = p.path("skus").path(0).path("sku").asString(null);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("productId", productId);
    params.put("v", p.path("version").asInt());
    params.put("categoryIds", categoryIds);
    params.put("scope", SearchRules.categoryScope(categoryIds));
    params.put("primary", p.path("primaryCategoryId").asString(null));
    params.put("sku", sku);
    params.put("saleState", p.path("saleState").asString(SearchRules.ON_SALE));
    params.put("atMs", millis(p.path("effectiveAt"), e));
    params.put("now", clock.millis());
    index.scriptedUpsert(OpenSearchIndex.PRODUCTS, productId, PRODUCT_SCRIPT, params);
    // 가격이 상품보다 먼저 왔으면 여기서 붙인다(상품 문서를 먼저 쓴 뒤 읽으므로 사이에 온 가격은 가격 쪽 update_by_query 가 잡는다)
    if (sku != null) {
      index
          .get(OpenSearchIndex.SKU_PRICES, sku)
          .ifPresent(
              pr ->
                  index.scriptedUpsert(
                      OpenSearchIndex.PRODUCTS,
                      productId,
                      PRODUCT_PRICE_SCRIPT,
                      Map.of(
                          "price", pr.path("price").asLong(),
                          "pv", pr.path("priceVersion").asInt(),
                          "now", clock.millis())));
    }
    log.debug("색인 상품 {} v{}", productId, params.get("v"));
  }

  void content(EventEnvelope e) {
    String productId = e.payload().path("productId").asString();
    cards
        .card(productId)
        .ifPresentOrElse(
            c -> {
              Map<String, Object> params = new LinkedHashMap<>();
              params.put("productId", productId);
              params.put(
                  "cv", Math.max(c.contentVersion(), e.payload().path("contentVersion").asInt()));
              params.put("name", c.name());
              params.put("imageUrl", c.imageUrl());
              params.put("atMs", millis(e.payload().path("publishedAt"), e));
              params.put("now", clock.millis());
              index.scriptedUpsert(OpenSearchIndex.PRODUCTS, productId, CONTENT_SCRIPT, params);
              log.debug("색인 콘텐츠 {} v{}", productId, params.get("cv"));
            },
            () -> log.warn("콘텐츠 사건을 받았지만 게시본이 없다 {} — 건너뜀", productId));
  }

  void saleState(EventEnvelope e) {
    JsonNode p = e.payload();
    String productId = p.path("productId").asString();
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("productId", productId);
    params.put(
        "state", SearchRules.saleStateFromEvent(p.path("state").asString(SearchRules.ON_SALE)));
    params.put("atMs", millis(p.path("effectiveAt"), e));
    params.put("now", clock.millis());
    index.scriptedUpsert(OpenSearchIndex.PRODUCTS, productId, SALE_STATE_SCRIPT, params);
  }

  void price(EventEnvelope e) {
    JsonNode p = e.payload();
    String sku = p.path("sku").asString();
    long price = p.path("price").asLong();
    int pv = p.path("priceVersion").asInt();
    Map<String, Object> skuParams = new LinkedHashMap<>();
    skuParams.put("sku", sku);
    skuParams.put("price", price);
    skuParams.put("pv", pv);
    skuParams.put("atMs", millis(p.path("effectiveAt"), e));
    index.scriptedUpsert(OpenSearchIndex.SKU_PRICES, sku, SKU_PRICE_SCRIPT, skuParams);
    index.updateByQuery(
        OpenSearchIndex.PRODUCTS,
        Map.of("term", Map.of("representativeSku", sku)),
        PRODUCT_PRICE_BY_QUERY_SCRIPT,
        Map.of("price", price, "pv", pv, "now", clock.millis()));
    log.debug("색인 가격 {} v{}", sku, pv);
  }

  private static long millis(JsonNode at, EventEnvelope e) {
    return at.isString()
        ? Instant.parse(at.asString()).toEpochMilli()
        : e.occurredAt().toEpochMilli();
  }
}
