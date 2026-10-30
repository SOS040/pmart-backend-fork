package com.pmart.search.application;

import com.pmart.search.domain.SearchRules;
import com.pmart.search.domain.SearchRules.Sort;
import com.pmart.search.infrastructure.OpenSearchIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 상품 검색·자동완성. 게시 콘텐츠(이름)와 상품 버전이 모두 색인된 상품만, 판매중지는 제외. 이름은 1~2글자 n-gram 이라 "미역" 으로 "미역국" 이 걸리고 검색어의
 * 모든 글자 조각이 있어야 한다(AND).
 *
 * <p>가격·판매 상태는 색인 시점 사본이다. 취급 여부(carried)는 MFC 취급 사건이 아직 없어 늘 불명(null) — 0 이나 false 로 채우지 않는다.
 */
@Service
public class ProductSearch {

  public record Hit(
      String productId,
      String name,
      @Nullable String imageUrl,
      List<String> categoryIds,
      @Nullable String representativeSku,
      @Nullable Long basePrice,
      @Nullable Integer priceVersion,
      String saleState,
      @Nullable Integer productVersion) {}

  public record Result(
      List<Hit> items, long total, @Nullable String nextCursor, Sort appliedSort) {}

  private final OpenSearchIndex index;

  public ProductSearch(OpenSearchIndex index) {
    this.index = index;
  }

  public Result search(
      @Nullable String q,
      @Nullable String categoryId,
      Sort sort,
      int size,
      @Nullable String cursor) {
    int from = SearchRules.offset(cursor);
    String query = SearchRules.normalizeQuery(q);
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("from", from);
    req.put("size", size);
    req.put("track_total_hits", true);
    req.put("query", query(query, categoryId));
    req.put("sort", sort(sort, query != null));
    JsonNode res = index.search(OpenSearchIndex.PRODUCTS, req);
    List<Hit> hits = new ArrayList<>();
    for (JsonNode h : res.path("hits").path("hits")) {
      hits.add(hit(h.path("_source")));
    }
    long total = res.path("hits").path("total").path("value").asLong(0);
    String next =
        from + hits.size() < total && !hits.isEmpty()
            ? SearchRules.cursor(from + hits.size())
            : null;
    return new Result(hits, total, next, sort);
  }

  /** 검색어를 포함하는 상품명(중복 제거, 관련도 순). */
  public List<String> suggestions(String q, int limit) {
    String query = SearchRules.normalizeQuery(q);
    if (query == null) {
      return List.of();
    }
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("size", limit * 2);
    req.put("_source", List.of("name"));
    req.put("query", query(query, null));
    JsonNode res = index.search(OpenSearchIndex.PRODUCTS, req);
    Set<String> names = new LinkedHashSet<>();
    for (JsonNode h : res.path("hits").path("hits")) {
      names.add(h.path("_source").path("name").asString());
      if (names.size() >= limit) {
        break;
      }
    }
    return List.copyOf(names);
  }

  static Map<String, Object> query(@Nullable String q, @Nullable String categoryId) {
    List<Object> filter = new ArrayList<>();
    filter.add(Map.of("exists", Map.of("field", "name")));
    filter.add(Map.of("exists", Map.of("field", "productVersion")));
    if (categoryId != null && !categoryId.isBlank()) {
      filter.add(Map.of("term", Map.of("categoryScope", categoryId)));
    }
    Map<String, Object> bool = new LinkedHashMap<>();
    bool.put(
        "must",
        q == null
            ? List.of(Map.of("match_all", Map.of()))
            : List.of(Map.of("match", Map.of("name", Map.of("query", q, "operator", "and")))));
    bool.put("filter", filter);
    bool.put("must_not", List.of(Map.of("term", Map.of("saleState", SearchRules.SALE_STOPPED))));
    return Map.of("bool", bool);
  }

  static List<Object> sort(Sort sort, boolean hasQuery) {
    Object newest = Map.of("contentPublishedAtMs", Map.of("order", "desc", "missing", "_last"));
    Object tie = Map.of("productId", Map.of("order", "asc"));
    return switch (sort) {
      case NEWEST -> List.of(newest, tie);
      case PRICE_ASC ->
          List.of(Map.of("basePrice", Map.of("order", "asc", "missing", "_last")), newest, tie);
      case PRICE_DESC ->
          List.of(Map.of("basePrice", Map.of("order", "desc", "missing", "_last")), newest, tie);
      case RELEVANCE ->
          hasQuery
              ? List.of(Map.of("_score", Map.of("order", "desc")), newest, tie)
              : List.of(newest, tie);
    };
  }

  private static Hit hit(JsonNode s) {
    List<String> categories = new ArrayList<>();
    s.path("categoryIds").forEach(c -> categories.add(c.asString()));
    return new Hit(
        s.path("productId").asString(),
        s.path("name").asString(),
        s.hasNonNull("imageUrl") ? s.get("imageUrl").asString() : null,
        categories,
        s.hasNonNull("representativeSku") ? s.get("representativeSku").asString() : null,
        s.hasNonNull("basePrice") ? s.get("basePrice").asLong() : null,
        s.hasNonNull("priceVersion") ? s.get("priceVersion").asInt() : null,
        s.path("saleState").asString(SearchRules.ON_SALE),
        s.hasNonNull("productVersion") ? s.get("productVersion").asInt() : null);
  }
}
