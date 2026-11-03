package com.pmart.search.application;

import com.pmart.search.infrastructure.SearchIndex;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 색인 신선도(GET /internal/index-status, 2026-11-03): 색인 문서의 시각 필드 최댓값으로 원천별 마지막 반영을 보여 준다. 읽기만 한다(색인은
 * Logstash, ADR-0031).
 *
 * <ul>
 *   <li>{@code ANY} — Logstash 가 마지막으로 반영한 시각(모든 사건, {@code updatedAtMs}). 색인이 멈췄는지 보는 기준.
 *   <li>{@code ProductContentPublished}·{@code ProductSaleStateChanged}·{@code
 *       PriceVersionEffective} — 마지막으로 반영된 사건의 <b>사건
 *       시각</b>(contentPublishedAtMs·saleStateAtMs·sku-prices effectiveAtMs).
 *   <li>{@code ProductVersionPublished} 는 따로 남는 시각이 없어 ANY 로 본다.
 * </ul>
 *
 * lagSeconds = 지금 − lastAppliedAt(마지막 반영 뒤 지난 시간). 사건이 없어서 조용한 것과 색인이 멈춘 것은 이 값만으로 구분되지 않는다 — 원천의
 * 최근 사건 시각과 함께 본다.
 */
@Service
public class IndexStatus {

  public record Source(String eventType, Instant lastAppliedAt, long lagSeconds) {}

  public record Status(long documents, List<Source> sources, Instant asOf) {}

  private final SearchIndex index;
  private final Clock clock;

  public IndexStatus(SearchIndex index, Clock clock) {
    this.index = index;
    this.clock = clock;
  }

  public Status status() {
    Instant now = clock.instant();
    Map<String, Object> productAggs = new LinkedHashMap<>();
    productAggs.put("any", max("updatedAtMs"));
    productAggs.put("content", max("contentPublishedAtMs"));
    productAggs.put("saleState", max("saleStateAtMs"));
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("size", 0);
    req.put("track_total_hits", true);
    req.put("aggs", productAggs);
    JsonNode products = index.search(SearchIndex.PRODUCTS, req);

    JsonNode prices =
        index.search(
            SearchIndex.SKU_PRICES,
            Map.of("size", 0, "aggs", Map.of("price", max("effectiveAtMs"))));

    List<Source> sources = new ArrayList<>();
    add(sources, "ANY", products.path("aggregations").path("any"), now);
    add(sources, "ProductContentPublished", products.path("aggregations").path("content"), now);
    add(sources, "ProductSaleStateChanged", products.path("aggregations").path("saleState"), now);
    add(sources, "PriceVersionEffective", prices.path("aggregations").path("price"), now);
    long documents = products.path("hits").path("total").path("value").asLong(0);
    return new Status(documents, sources, now);
  }

  private static Map<String, Object> max(String field) {
    return Map.of("max", Map.of("field", field));
  }

  /** 값이 없으면(문서 없음·그 사건이 아직 없음) 항목을 넣지 않는다 — 0 이나 지어낸 시각을 주지 않는다. */
  private static void add(List<Source> out, String eventType, JsonNode agg, Instant now) {
    @Nullable JsonNode value = agg.path("value");
    if (value.isMissingNode() || value.isNull()) {
      return;
    }
    Instant at = Instant.ofEpochMilli((long) value.asDouble());
    out.add(new Source(eventType, at, Math.max(0, now.getEpochSecond() - at.getEpochSecond())));
  }
}
