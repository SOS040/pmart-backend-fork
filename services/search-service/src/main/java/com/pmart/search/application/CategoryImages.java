package com.pmart.search.application;

import com.pmart.search.domain.SearchRules;
import com.pmart.search.infrastructure.SearchIndex;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 카테고리 대표 사진(계약 0.13.0, 2026-11-23 고객앱 BR-89 — 사용자 "대표 상품 사진 자동"). 카테고리(categoryScope — 하위 포함)마다 판매
 * 가능 상품 중 최근 판매 많은 → 별점 → 최근 게시 순 1위의 대표 사진. 센터마다 5분 사본(한 번에 모든 카테고리 — ES terms + top_hits 한 번).
 */
@Service
public class CategoryImages {

  static final Duration TTL = Duration.ofMinutes(5);
  static final int MAX_CATEGORIES = 2000;

  public record Item(String categoryId, String imageUrl, String productId) {}

  private record Snapshot(List<Item> items, Instant at) {}

  private final SearchIndex index;
  private final Clock clock;
  private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

  public CategoryImages(SearchIndex index, Clock clock) {
    this.index = index;
    this.clock = clock;
  }

  public List<Item> of(@Nullable String mfcId) {
    String key = mfcId == null || mfcId.isBlank() ? "" : mfcId;
    Instant now = clock.instant();
    Snapshot s = cache.get(key);
    if (s != null && s.at().plus(TTL).isAfter(now)) {
      return s.items();
    }
    List<Item> items = load(key.isEmpty() ? null : key);
    cache.put(key, new Snapshot(items, now));
    return items;
  }

  private List<Item> load(@Nullable String center) {
    List<Object> filter = new ArrayList<>();
    filter.add(Map.of("exists", Map.of("field", "name")));
    filter.add(Map.of("exists", Map.of("field", "productVersion")));
    if (center != null) {
      filter.add(Map.of("term", Map.of("carriedMfcIds", center)));
    }
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("size", 0);
    req.put(
        "query",
        Map.of(
            "bool",
            Map.of(
                "filter",
                filter,
                "must_not",
                List.of(Map.of("term", Map.of("saleState", SearchRules.SALE_STOPPED))))));
    req.put(
        "aggs",
        Map.of(
            "c",
            Map.of(
                "terms",
                Map.of("field", "categoryScope", "size", MAX_CATEGORIES),
                "aggs",
                Map.of(
                    "top",
                    Map.of(
                        "top_hits",
                        Map.of(
                            "size",
                            3, // 사진 없는 상품을 건너뛰려고 몇 개
                            "_source",
                            List.of("imageUrl", "productId"),
                            "sort",
                            List.of(
                                Map.of("salesWindow", Map.of("order", "desc", "missing", "_last")),
                                Map.of("ratingScore", Map.of("order", "desc", "missing", "_last")),
                                Map.of(
                                    "contentPublishedAtMs",
                                    Map.of("order", "desc", "missing", "_last")),
                                Map.of("productId", Map.of("order", "asc")))))))));
    List<Item> out = new ArrayList<>();
    for (JsonNode b :
        index.search(SearchIndex.PRODUCTS, req).path("aggregations").path("c").path("buckets")) {
      for (JsonNode h : b.path("top").path("hits").path("hits")) {
        JsonNode src = h.path("_source");
        if (src.hasNonNull("imageUrl") && !src.get("imageUrl").asString().isBlank()) {
          out.add(
              new Item(
                  b.path("key").asString(),
                  src.get("imageUrl").asString(),
                  src.path("productId").asString()));
          break;
        }
      }
    }
    return List.copyOf(out);
  }
}
