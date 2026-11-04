package com.pmart.search.application;

import com.pmart.search.domain.SearchRules;
import com.pmart.search.domain.SearchRules.Sort;
import com.pmart.search.infrastructure.AvailabilityClient;
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
 * <p>가격·판매 상태는 색인 시점 사본이다. 카테고리 이름 경로는 product-service 공개 트리 사본(5분), 취급 여부(carried)는 mfcId 를 줄 때만
 * mfc-inventory 사본으로 붙인다(대표 SKU 기준, 조회 실패·응답 없음은 불명 null — false 로 채우지 않는다).
 * includeNotCarried=false 면 취급하지 않음(false)이 확인된 상품만 빼고(불명은 남긴다), 모자란 만큼 다음 문서를 더 읽어 페이지를
 * 채운다(BR-47). total 은 색인 일치 건수(미취급 포함)이고, 처음부터 끝까지 한 번에 다 읽은 경우만 실제 건수로 맞춘다.
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
  private final AvailabilityClient availability;

  public ProductSearch(
      SearchIndex index, CategoryTreeClient categories, AvailabilityClient availability) {
    this.index = index;
    this.categories = categories;
    this.availability = availability;
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
    boolean filtering = mfcId != null && !mfcId.isBlank() && !includeNotCarried;
    List<Hit> out = new ArrayList<>();
    int pos = from;
    long total = 0;
    int rounds = 0;
    // BR-47: 미취급을 빼서 모자라면 다음 문서를 더 읽어 채운다(최대 FILL_ROUNDS 번). 다음 커서는 실제로 읽은 위치 뒤
    while (true) {
      Map<String, Object> req = new LinkedHashMap<>();
      req.put("from", pos);
      req.put("size", size);
      req.put("track_total_hits", true);
      req.put("query", query(query, categoryId, price, filters));
      req.put("sort", sort(sort, query != null));
      JsonNode res = index.search(SearchIndex.PRODUCTS, req);
      List<Hit> hits = new ArrayList<>();
      for (JsonNode h : res.path("hits").path("hits")) {
        hits.add(hit(h.path("_source")));
      }
      total = res.path("hits").path("total").path("value").asLong(0);
      List<@Nullable Hit> enriched = enrich(hits, mfcId, includeNotCarried);
      for (Hit h : enriched) {
        pos++;
        if (h != null) {
          out.add(h);
          if (out.size() >= size) {
            break;
          }
        }
      }
      rounds++;
      if (hits.isEmpty()
          || out.size() >= size
          || pos >= total
          || !filtering
          || rounds > FILL_ROUNDS) {
        break;
      }
    }
    String next = pos < total ? SearchRules.cursor(pos) : null;
    // 처음부터 끝까지 한 번에 다 읽었으면 total 을 실제 건수로 맞춘다. 아니면 색인 일치 건수(미취급 포함, 계약 설명)
    long shownTotal = filtering && from == 0 && next == null ? out.size() : total;
    List<String> applied = new ArrayList<>();
    filters.forEach((k, vs) -> vs.forEach(v -> applied.add(k + "." + v)));
    return new Result(out, shownTotal, next, sort, List.copyOf(applied));
  }

  /** 미취급을 빼고 페이지를 채우려고 더 읽는 최대 횟수(한 번에 size 개). */
  static final int FILL_ROUNDS = 5;

  /**
   * 이름 경로(대표 카테고리)·취급 여부를 붙인다. 입력과 같은 순서·길이로 돌려주고, 취급하지 않음이 확인된 상품은 includeNotCarried=false 면
   * null(뺌)이다 — 호출자가 읽은 위치를 셀 수 있게.
   */
  private List<@Nullable Hit> enrich(
      List<Hit> hits, @Nullable String mfcId, boolean includeNotCarried) {
    Map<String, Boolean> carried = Map.of();
    if (mfcId != null && !mfcId.isBlank()) {
      List<String> skus =
          hits.stream()
              .map(Hit::representativeSku)
              .filter(java.util.Objects::nonNull)
              .distinct()
              .toList();
      carried = skus.isEmpty() ? Map.of() : availability.carried(mfcId, skus);
    }
    List<@Nullable Hit> out = new ArrayList<>();
    for (Hit h : hits) {
      String primary =
          h.primaryCategoryId() != null
              ? h.primaryCategoryId()
              : (h.categoryIds().isEmpty() ? null : h.categoryIds().get(0));
      Boolean c = h.representativeSku() == null ? null : carried.get(h.representativeSku());
      if (!includeNotCarried && Boolean.FALSE.equals(c)) {
        out.add(null);
        continue;
      }
      out.add(
          new Hit(
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
              c));
    }
    return out;
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
    List<Object> filter = new ArrayList<>();
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
    } else {
      // 찾는 조건: 형태소(nori)가 모두 맞거나("두부를"→두부), 1~2글자 조각이 모두 있거나(붙임말 "나랑드사이다제로"·부분 "미역")
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
                              Map.of("name.partial", Map.of("query", q, "operator", "and")))),
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
        s.hasNonNull("productVersion") ? s.get("productVersion").asInt() : null,
        s.hasNonNull("primaryCategoryId") ? s.get("primaryCategoryId").asString() : null,
        List.of(),
        null);
  }
}
