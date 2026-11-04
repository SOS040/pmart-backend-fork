package com.pmart.search.application;

import com.pmart.search.domain.SearchRules;
import com.pmart.search.domain.SearchRules.Sort;
import com.pmart.search.infrastructure.CategoryTreeClient;
import com.pmart.search.infrastructure.SearchIndex;
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
 * 상품 검색·자동완성(Elasticsearch, ADR-0031). 게시 콘텐츠(이름)와 상품 버전이 모두 색인된 상품만, 판매중지는 제외. 이름은 nori 형태소가 모두
 * 맞거나 1~2글자 n-gram 조각이 모두 있으면 걸린다("두부를"→두부, "미역"→미역국, 붙임말 "나랑드사이다제로"). 순위는 형태소 > 앞부분 > 조각 일치.
 *
 * <p>가격·판매 상태는 색인 시점 사본이다. 카테고리 이름 경로는 product-service 공개 트리 사본(5분). 취급 여부(carried)는 mfcId 를 줄 때만,
 * 색인의 {@code carriedMfcIds}(대표 SKU 를 켠 센터 목록 — mfc-inventory SkuHandlingChanged, 2026-11-05)로 정한다.
 * 취급 레코드가 없으면 OFF(mfc-inventory 규칙)라 false 다. includeNotCarried=false 면 색인에서 바로 거르므로 페이지 크기·total 이
 * 정확하다 (이전의 실시간 가용 조회와 "더 읽어 채우기"는 없앴다). 품절은 색인하지 않는다(화면 표시 때 실시간 확인 — 사용자 결정 2026-11-05).
 *
 * <p>검색어가 초성으로만 된 경우(2자 이상, 예 "ㄷㅂ")는 상품명 초성 필드({@code nameChosung})에서 찾는다.
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
      @Nullable Integer productVersion,
      @Nullable String primaryCategoryId,
      List<String> categoryPath,
      @Nullable Boolean carried) {}

  public record Result(
      List<Hit> items,
      long total,
      @Nullable String nextCursor,
      Sort appliedSort,
      List<String> appliedFilters) {}

  /** 선택 가능한 필터 값과 현재 조건의 건수(색인 집계). */
  public record Facet(String key, String value, long count) {}

  /** 기본가 범위(원, 양 끝 포함). 둘 다 null 이면 거르지 않는다. 가격 사본이 없는 상품은 범위를 주면 빠진다. */
  public record PriceRange(@Nullable Long min, @Nullable Long max) {
    public static final PriceRange ANY = new PriceRange(null, null);

    public PriceRange {
      if (min != null && max != null && min > max) {
        throw new IllegalArgumentException("minPrice > maxPrice");
      }
    }

    boolean any() {
      return min == null && max == null;
    }
  }

  private final SearchIndex index;
  private final CategoryTreeClient categories;

  public ProductSearch(SearchIndex index, CategoryTreeClient categories) {
    this.index = index;
    this.categories = categories;
  }

  public Result search(
      @Nullable String q,
      @Nullable String categoryId,
      Sort sort,
      int size,
      @Nullable String cursor) {
    return search(q, categoryId, null, true, PriceRange.ANY, Map.of(), sort, size, cursor);
  }

  public Result search(
      @Nullable String q,
      @Nullable String categoryId,
      @Nullable String mfcId,
      boolean includeNotCarried,
      PriceRange price,
      Map<String, List<String>> filters,
      Sort sort,
      int size,
      @Nullable String cursor) {
    int from = SearchRules.offset(cursor);
    String query = SearchRules.normalizeQuery(q);
    String center = mfcId == null || mfcId.isBlank() ? null : mfcId;
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("from", from);
    req.put("size", size);
    req.put("track_total_hits", true);
    req.put("query", query(query, categoryId, price, filters, includeNotCarried ? null : center));
    req.put("sort", sort(sort, query != null));
    JsonNode res = index.search(SearchIndex.PRODUCTS, req);
    List<Hit> out = new ArrayList<>();
    for (JsonNode h : res.path("hits").path("hits")) {
      out.add(withPath(hit(h.path("_source"), center)));
    }
    long total = res.path("hits").path("total").path("value").asLong(0);
    int pos = from + out.size();
    String next = !out.isEmpty() && pos < total ? SearchRules.cursor(pos) : null;
    List<String> applied = new ArrayList<>();
    filters.forEach((k, vs) -> vs.forEach(v -> applied.add(k + "." + v)));
    return new Result(out, total, next, sort, List.copyOf(applied));
  }

  /** 이름 경로(대표 카테고리 — 없으면 첫 카테고리)를 붙인다. */
  private Hit withPath(Hit h) {
    String primary =
        h.primaryCategoryId() != null
            ? h.primaryCategoryId()
            : (h.categoryIds().isEmpty() ? null : h.categoryIds().get(0));
    return new Hit(
        h.productId(),
        h.name(),
        h.imageUrl(),
        h.categoryIds(),
        h.representativeSku(),
        h.basePrice(),
        h.priceVersion(),
        h.saleState(),
        h.productVersion(),
        primary,
        primary == null ? List.of() : categories.path(primary),
        h.carried());
  }

  /** 검색어를 포함하는 상품명(중복 제거, 관련도 순). */
  /** 현재 조건(검색어·카테고리, 판매중지 제외)에서 선택 가능한 승인 속성 값과 건수 — 색인 {@code filters}("그룹.옵션") 집계(2026-11-04). */
  public List<Facet> facets(@Nullable String q, @Nullable String categoryId) {
    String query = SearchRules.normalizeQuery(q);
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("size", 0);
    req.put("query", query(query, categoryId, PriceRange.ANY, Map.of()));
    req.put("aggs", Map.of("f", Map.of("terms", Map.of("field", "filters", "size", 500))));
    JsonNode res = index.search(SearchIndex.PRODUCTS, req);
    List<Facet> out = new ArrayList<>();
    for (JsonNode b : res.path("aggregations").path("f").path("buckets")) {
      String key = b.path("key").asString("");
      int dot = key.indexOf('.');
      if (dot > 0 && dot < key.length() - 1) {
        out.add(
            new Facet(key.substring(0, dot), key.substring(dot + 1), b.path("doc_count").asLong()));
      }
    }
    return out;
  }

  public List<String> suggestions(String q, int limit) {
    String query = SearchRules.normalizeQuery(q);
    if (query == null) {
      return List.of();
    }
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("size", limit * 2);
    req.put("_source", List.of("name"));
    req.put("query", query(query, null, PriceRange.ANY, Map.of()));
    JsonNode res = index.search(SearchIndex.PRODUCTS, req);
    Set<String> names = new LinkedHashSet<>();
    for (JsonNode h : res.path("hits").path("hits")) {
      names.add(h.path("_source").path("name").asString());
      if (names.size() >= limit) {
        break;
      }
    }
    return List.copyOf(names);
  }

  static Map<String, Object> query(
      @Nullable String q,
      @Nullable String categoryId,
      PriceRange price,
      Map<String, List<String>> filters) {
    return query(q, categoryId, price, filters, null);
  }

  /**
   * @param carriedAt 이 센터가 대표 SKU 를 취급하는 상품만(색인 carriedMfcIds). null 이면 거르지 않는다
   */
  static Map<String, Object> query(
      @Nullable String q,
      @Nullable String categoryId,
      PriceRange price,
      Map<String, List<String>> filters,
      @Nullable String carriedAt) {
    List<Object> filter = new ArrayList<>();
    if (carriedAt != null) {
      filter.add(Map.of("term", Map.of("carriedMfcIds", carriedAt)));
    }
    // 승인 속성 필터: 같은 그룹 안의 값은 OR(terms), 그룹끼리는 AND(2026-11-04)
    filters.forEach(
        (group, values) ->
            filter.add(
                Map.of(
                    "terms",
                    Map.of("filters", values.stream().map(v -> group + "." + v).toList()))));
    filter.add(Map.of("exists", Map.of("field", "name")));
    filter.add(Map.of("exists", Map.of("field", "productVersion")));
    if (categoryId != null && !categoryId.isBlank()) {
      filter.add(Map.of("term", Map.of("categoryScope", categoryId)));
    }
    if (!price.any()) {
      Map<String, Object> range = new LinkedHashMap<>();
      if (price.min() != null) {
        range.put("gte", price.min());
      }
      if (price.max() != null) {
        range.put("lte", price.max());
      }
      filter.add(Map.of("range", Map.of("basePrice", range)));
    }
    Map<String, Object> bool = new LinkedHashMap<>();
    if (q == null) {
      bool.put("must", List.of(Map.of("match_all", Map.of())));
    } else if (SearchRules.isChosungQuery(q)) {
      // 초성만 된 검색어: 상품명 초성(공백을 뺀 1~2글자 조각)이 모두 있으면 걸린다("ㅅㅋㄹ" → 생크림·생 크림)
      bool.put(
          "must",
          List.of(Map.of("match", Map.of("nameChosung", Map.of("query", q, "operator", "and")))));
    } else {
      // 찾는 조건: 형태소(nori)가 모두 맞거나("두부를"→두부), 1~2글자 조각이 모두 있거나(붙임말 "나랑드사이다제로"·부분 "미역"),
      // 공백을 모두 뺀 이름·검색어의 조각이 모두 있거나(붙여 쓴 "생크림" ↔ 띄어 쓴 "생 크림", products-v4 name.compact)
      bool.put(
          "must",
          List.of(
              Map.of(
                  "bool",
                  Map.of(
                      "should",
                      List.of(
                          Map.of("match", Map.of("name", Map.of("query", q, "operator", "and"))),
                          Map.of(
                              "match",
                              Map.of("name.partial", Map.of("query", q, "operator", "and"))),
                          Map.of(
                              "match",
                              Map.of("name.compact", Map.of("query", q, "operator", "and")))),
                      "minimum_should_match",
                      1))));
      // 순위: 형태소 일치 > 앞부분 일치 > 조각 일치
      bool.put(
          "should",
          List.of(
              Map.of("match", Map.of("name", Map.of("query", q, "boost", 3))),
              Map.of("match", Map.of("name.edge", Map.of("query", q, "boost", 2)))));
    }
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

  /** 색인 문서 → Hit. carried 는 센터를 줄 때만(그 센터가 carriedMfcIds 에 있는지), 아니면 null. */
  private static Hit hit(JsonNode s, @Nullable String center) {
    Boolean carried = null;
    if (center != null) {
      carried = false;
      for (JsonNode m : s.path("carriedMfcIds")) {
        if (center.equals(m.asString())) {
          carried = true;
          break;
        }
      }
    }
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
        s.hasNonNull("productVersion") ? s.get("productVersion").asInt() : null,
        s.hasNonNull("primaryCategoryId") ? s.get("primaryCategoryId").asString() : null,
        List.of(),
        carried);
  }
}
