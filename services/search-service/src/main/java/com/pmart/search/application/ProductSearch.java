package com.pmart.search.application;

import com.pmart.search.domain.LabelRules;
import com.pmart.search.domain.SearchRules;
import com.pmart.search.domain.SearchRules.Sort;
import com.pmart.search.infrastructure.CategoryTreeClient;
import com.pmart.search.infrastructure.SearchIndex;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(ProductSearch.class);

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
      @Nullable Boolean carried,
      @Nullable Double ratingAverage,
      @Nullable Integer ratingCount,
      @Nullable Discount discount,
      @Nullable JsonNode nutrition,
      @Nullable JsonNode allergens) {

    Hit withDiscount(@Nullable Discount d) {
      return new Hit(
          productId,
          name,
          imageUrl,
          categoryIds,
          representativeSku,
          basePrice,
          priceVersion,
          saleState,
          productVersion,
          primaryCategoryId,
          categoryPath,
          carried,
          ratingAverage,
          ratingCount,
          d,
          nutrition,
          allergens);
    }
  }

  /**
   * 대표 SKU 의 기간 할인(ADR-0082 4단계, 색인 sku-discounts — promotion SkuDisplayDiscountChanged 사본). 표시용.
   */
  public record Discount(
      long salePrice, int ratePercent, @Nullable String name, @Nullable Instant endsAt) {}

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

  /** 결과 항목을 만드는 데 읽는 색인 칸({@link #hit}) — 이것만 가져온다. */
  static final List<String> HIT_FIELDS =
      List.of(
          "productId",
          "name",
          "imageUrl",
          "categoryIds",
          "representativeSku",
          "basePrice",
          "priceVersion",
          "saleState",
          "productVersion",
          "primaryCategoryId",
          "carriedMfcIds",
          "ratingAverage",
          "ratingCount",
          "nutrition",
          "allergens");

  private final SearchIndex index;
  private final CategoryTreeClient categories;
  private final Clock clock;
  private final LabelRules.@Nullable Thresholds nutrition;

  public ProductSearch(
      SearchIndex index, CategoryTreeClient categories, Clock clock, SearchProperties props) {
    this.nutrition = props.nutrition() == null ? null : props.nutrition().thresholds();
    this.index = index;
    this.categories = categories;
    this.clock = clock;
  }

  public Result search(
      @Nullable String q,
      @Nullable String categoryId,
      @Nullable String mfcId,
      boolean includeNotCarried,
      PriceRange price,
      Map<String, List<String>> filters,
      boolean onSale,
      Sort sort,
      int size,
      @Nullable String cursor) {
    int from = SearchRules.offset(cursor);
    String query = SearchRules.normalizeQuery(q);
    String center = mfcId == null || mfcId.isBlank() ? null : mfcId;
    boolean byDiscount = sort == Sort.DISCOUNT_RATE;
    if (byDiscount && !onSale) {
      throw new com.pmart.common.web.PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED,
          "할인율순은 할인 상품 목록(onSale=true)에서만 쓸 수 있습니다.");
    }
    Map<String, Object> req = new LinkedHashMap<>();
    // 할인율순(0.9.0 BR-83): 할인 상품 전부(상한 MAX_ON_SALE_SKUS — onSale 거르기와 같은 수)를 읽어 할인 사본으로 줄 세운 뒤 쪽을 자른다
    req.put("from", byDiscount ? 0 : from);
    req.put(
        "size",
        byDiscount
            ? SearchRules.MAX_ON_SALE_SKUS
            : SearchRules.pageSize(from, size)); // 1순위 ① — from + size 가 ES 창(1만)을 넘지 않게
    req.put("track_total_hits", true);
    req.put("_source", HIT_FIELDS); // 결과에 쓰는 칸만(설명·원산지 글·초성 등 긴 칸은 찾기에만 — 3순위 ⑥, 2026-11-23)
    Map<String, List<String>> rest = new LinkedHashMap<>(filters);
    List<String> nut = rest.remove(LabelRules.NUTRITION_KEY);
    req.put(
        "query",
        query(
            query,
            categoryId,
            price,
            rest,
            includeNotCarried ? null : center,
            namesFor(rest),
            extra(onSale ? onSaleFilter() : null, nut)));
    req.put("sort", sort(sort, query != null));
    JsonNode res = index.search(SearchIndex.PRODUCTS, req);
    List<Hit> out = new ArrayList<>();
    for (JsonNode h : res.path("hits").path("hits")) {
      out.add(withPath(hit(h.path("_source"), center)));
    }
    out = withDiscounts(out);
    long total = res.path("hits").path("total").path("value").asLong(0);
    if (byDiscount) {
      out = SearchRules.pageOf(sortByDiscount(out), from, size);
    }
    int pos = from + out.size();
    String next = SearchRules.hasNext(pos, total, !out.isEmpty()) ? SearchRules.cursor(pos) : null;
    List<String> applied = new ArrayList<>();
    filters.forEach((k, vs) -> vs.forEach(v -> applied.add(k + "." + v)));
    return new Result(out, total, next, sort, List.copyOf(applied));
  }

  /**
   * 덧붙일 거르기 — 할인 중(onSale)과 영양(products-v9 0.11.0, 값 모두 만족). 영양을 골랐는데 기준값 설정이 없으면 422
   * CONFIG_REQUIRED(기준을 지어내지 않는다).
   */
  private @Nullable Object extra(@Nullable Object onSale, @Nullable List<String> nut) {
    List<Object> all = new ArrayList<>();
    if (onSale != null) {
      all.add(onSale);
    }
    if (nut != null && !nut.isEmpty()) {
      if (nutrition == null) {
        throw new com.pmart.common.web.PmartException(
            com.pmart.common.web.ErrorCode.CONFIG_REQUIRED, "영양 필터 기준값이 설정되지 않았습니다.");
      }
      all.add(LabelRules.nutritionFilter(nut, nutrition));
    }
    return all.isEmpty()
        ? null
        : all.size() == 1 ? all.get(0) : Map.of("bool", Map.of("filter", all));
  }

  /**
   * 할인 중인 상품만(2026-11-22 클라이언트 BR-82, 계약 0.8.0 {@code onSale=true}). 할인 색인에서 지금 할인 중인
   * SKU(unitDiscount > 0, 끝 시각 전이거나 없음)와 가격 버전을 읽어, 대표 SKU·가격 버전이 같은 상품만 남긴다 — 결과에 할인가를 붙이는 {@link
   * #discount} 와 같은 판정. 할인 상품이 없으면 아무것도 맞지 않는다. 할인 색인을 못 읽으면 503(할인 여부를 모르는데 전체를 보여 주지 않는다).
   */
  private Object onSaleFilter() {
    long now = clock.millis();
    JsonNode res;
    try {
      res =
          index.search(
              SearchIndex.SKU_DISCOUNTS,
              Map.of(
                  "size",
                  SearchRules.MAX_ON_SALE_SKUS,
                  "_source",
                  List.of("sku", "priceVersion"),
                  "query",
                  Map.of(
                      "bool",
                      Map.of(
                          "filter",
                          List.of(Map.of("range", Map.of("unitDiscount", Map.of("gt", 0)))),
                          "should",
                          List.of(
                              Map.of("range", Map.of("endsAtMs", Map.of("gt", now))),
                              Map.of(
                                  "bool",
                                  Map.of(
                                      "must_not",
                                      List.of(Map.of("exists", Map.of("field", "endsAtMs")))))),
                          "minimum_should_match",
                          1))));
    } catch (RuntimeException ex) {
      throw com.pmart.common.web.PmartException.dependencyUnavailable("할인 상품을 확인할 수 없습니다.", ex);
    }
    List<Object> any = new ArrayList<>();
    for (JsonNode d : res.path("hits").path("hits")) {
      JsonNode s = d.path("_source");
      if (s.hasNonNull("sku") && s.path("priceVersion").isNumber()) {
        any.add(
            Map.of(
                "bool",
                Map.of(
                    "filter",
                    List.of(
                        Map.of("term", Map.of("representativeSku", s.path("sku").asString())),
                        Map.of("term", Map.of("priceVersion", s.path("priceVersion").asInt()))))));
      }
    }
    if (any.isEmpty()) {
      return Map.of("match_none", Map.of());
    }
    return Map.of("bool", Map.of("should", any, "minimum_should_match", 1));
  }

  /** 할인율 높은 순 → 할인가 낮은 순 → 상품 ID(할인이 없는 항목은 뒤 — onSale 결과라 보통 없다). */
  static List<Hit> sortByDiscount(List<Hit> hits) {
    List<Hit> sorted = new ArrayList<>(hits);
    sorted.sort(
        java.util.Comparator.comparingInt(
                (Hit h) -> h.discount() == null ? Integer.MIN_VALUE : h.discount().ratePercent())
            .reversed()
            .thenComparingLong(
                h -> h.discount() == null ? Long.MAX_VALUE : h.discount().salePrice())
            .thenComparing(Hit::productId));
    return sorted;
  }

  /**
   * 쪽의 대표 SKU 할인을 붙인다(ADR-0082 4단계). 가격 사본과 같은 priceVersion·할인 있음·끝 전일 때만. 할인 색인을 못 읽으면 정가로(검색은
   * 실패시키지 않는다). 가격순 정렬·가격 범위는 기본가 기준 그대로.
   */
  private List<Hit> withDiscounts(List<Hit> hits) {
    List<String> skus =
        hits.stream().map(Hit::representativeSku).filter(java.util.Objects::nonNull).toList();
    if (skus.isEmpty()) {
      return hits;
    }
    Map<String, JsonNode> bySku = new java.util.HashMap<>();
    try {
      JsonNode res =
          index.search(
              SearchIndex.SKU_DISCOUNTS,
              Map.of("size", skus.size(), "query", Map.of("terms", Map.of("sku", skus))));
      for (JsonNode d : res.path("hits").path("hits")) {
        bySku.put(d.path("_source").path("sku").asString(), d.path("_source"));
      }
    } catch (RuntimeException ex) {
      log.warn("할인 색인을 못 읽음 — 정가로: {}", ex.toString());
      return hits;
    }
    long now = clock.millis();
    List<Hit> out = new ArrayList<>(hits.size());
    for (Hit h : hits) {
      JsonNode d = h.representativeSku() == null ? null : bySku.get(h.representativeSku());
      out.add(h.withDiscount(discount(d, h.priceVersion(), now)));
    }
    return out;
  }

  static @Nullable Discount discount(
      @Nullable JsonNode d, @Nullable Integer priceVersion, long now) {
    if (d == null
        || priceVersion == null
        || d.path("priceVersion").asInt(-1) != priceVersion
        || d.path("unitDiscount").asLong(0) <= 0
        || (d.path("endsAtMs").isNumber() && d.path("endsAtMs").asLong() <= now)) {
      return null;
    }
    return new Discount(
        d.path("salePrice").asLong(),
        d.path("ratePercent").asInt(0),
        d.path("promotionName").asString(null),
        d.path("endsAtMs").isNumber() ? Instant.ofEpochMilli(d.path("endsAtMs").asLong()) : null);
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
        h.carried(),
        h.ratingAverage(),
        h.ratingCount(),
        h.discount(),
        h.nutrition(),
        h.allergens());
  }

  /** 검색어를 포함하는 상품명(중복 제거, 관련도 순). */
  /** 이름 키가 있을 때만 그룹 이름 사본을 읽는다(없으면 빈 맵 — 패널 읽기 없음). */
  private Map<String, String> namesFor(Map<String, List<String>> filters) {
    return filters.keySet().stream().anyMatch(SearchRules::isNameFilterKey)
        ? groupNames()
        : Map.of();
  }

  /** 모든 카테고리 패널의 그룹 id → 이름(필터 패널 사본). */
  public Map<String, String> groupNames() {
    Map<String, String> out = new LinkedHashMap<>();
    categories.allFilterGroups().forEach((id, g) -> out.put(id, g.name()));
    return out;
  }

  /** 현재 조건(검색어·카테고리, 판매중지 제외)에서 선택 가능한 승인 속성 값과 건수 — 색인 {@code filters}("그룹.옵션") 집계(2026-11-04). */
  public List<Facet> facets(@Nullable String q, @Nullable String categoryId) {
    return facets(q, categoryId, false);
  }

  /** onSale(0.9.0 BR-83) 이면 할인 중인 상품만으로 센다 — 검색 onSale 과 같은 거르기. */
  public List<Facet> facets(@Nullable String q, @Nullable String categoryId, boolean onSale) {
    String query = SearchRules.normalizeQuery(q);
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("size", 0);
    req.put(
        "query",
        query(
            query,
            categoryId,
            PriceRange.ANY,
            Map.of(),
            null,
            Map.of(),
            onSale ? onSaleFilter() : null));
    Map<String, Object> aggs = new LinkedHashMap<>();
    aggs.put("f", Map.of("terms", Map.of("field", "filters", "size", 500)));
    aggs.put("o", Map.of("terms", Map.of("field", "originType", "size", 5)));
    if (nutrition != null) { // products-v9 영양 — 값마다 그 조건에 맞는 건수
      Map<String, Object> named = new LinkedHashMap<>();
      for (String o : LabelRules.NUTRITION_OPTIONS) {
        named.put(o, LabelRules.nutritionClause(o, nutrition));
      }
      aggs.put("n", Map.of("filters", Map.of("filters", named)));
    }
    req.put("aggs", aggs);
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
    for (JsonNode b : res.path("aggregations").path("o").path("buckets")) {
      String label = SearchRules.originLabel(b.path("key").asString(""));
      if (label != null) {
        out.add(new Facet(SearchRules.ORIGIN_KEY, label, b.path("doc_count").asLong()));
      }
    }
    JsonNode nb = res.path("aggregations").path("n").path("buckets");
    for (String o : LabelRules.NUTRITION_OPTIONS) {
      long c = nb.path(o).path("doc_count").asLong(0);
      if (c > 0) {
        out.add(new Facet(LabelRules.NUTRITION_KEY, o, c));
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
    return query(q, categoryId, price, filters, null, Map.of(), null);
  }

  /**
   * @param carriedAt 이 센터가 대표 SKU 를 취급하는 상품만(색인 carriedMfcIds). null 이면 거르지 않는다
   * @param names 그룹 id → 이름(이름 키 n:<이름> 을 풀 때만 — {@link #namesFor})
   */
  static Map<String, Object> query(
      @Nullable String q,
      @Nullable String categoryId,
      PriceRange price,
      Map<String, List<String>> filters,
      @Nullable String carriedAt,
      Map<String, String> names,
      @Nullable Object onSale) {
    List<Object> filter = new ArrayList<>();
    if (onSale != null) {
      filter.add(onSale);
    }
    if (carriedAt != null) {
      filter.add(Map.of("term", Map.of("carriedMfcIds", carriedAt)));
    }
    // 승인 속성 필터: 같은 그룹 안의 값은 OR(terms), 그룹끼리는 AND(2026-11-04).
    // 이름 키(n:<그룹 이름>, 2026-11-22 BR-81)는 같은 이름의 그룹 id 모두로 — 카테고리마다 따로 정의된 "중량" 등을 한 줄로 고른다.
    filters.forEach(
        (group, values) -> {
          if (SearchRules.ORIGIN_KEY.equals(group)) { // 원산지 국내산·수입산(products-v8, 2026-11-22)
            filter.add(Map.of("terms", Map.of("originType", SearchRules.originTypes(values))));
            return;
          }
          List<String> ids = SearchRules.filterGroupIds(group, names);
          List<String> terms = new ArrayList<>();
          ids.forEach(id -> values.forEach(v -> terms.add(id + "." + v)));
          filter.add(Map.of("terms", Map.of("filters", terms)));
        });
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
      // 초성만 된 검색어: 상품명 초성(공백을 뺀 1~2글자 조각)이 모두 있으면 걸린다("ㅅㅋㄹ" → 생크림·생 크림).
      // 순위: 낱말 앞부분이 초성과 맞으면 가산("ㄷㅂ" → 두부가 "단백질"·"더바른"보다 위, products-v5 nameChosung.edge)
      bool.put(
          "must",
          List.of(Map.of("match", Map.of("nameChosung", Map.of("query", q, "operator", "and")))));
      bool.put(
          "should",
          List.of(Map.of("match", Map.of("nameChosung.edge", Map.of("query", q, "boost", 3)))));
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
                              Map.of("name.compact", Map.of("query", q, "operator", "and"))),
                          // products-v8(2026-11-22): 낱말이 이름·원산지 글·설명에 나뉘어 있어도 모두 있으면("국내산 삼겹살", "아이
                          // 간식").
                          // 낮은 비중 — 이름으로 찾은 상품이 늘 앞(이름 일치는 아래 should 가산을 더 받는다)
                          Map.of(
                              "multi_match",
                              Map.of(
                                  "query",
                                  q,
                                  "type",
                                  "cross_fields",
                                  "fields",
                                  List.of("name", "originText", "description"),
                                  "operator",
                                  "and",
                                  "boost",
                                  SearchRules.CONTENT_MATCH_BOOST)),
                          // products-v9(0.11.0): 알레르기를 묻는 검색어("땅콩 함유", "계란 알레르기")면 그 성분을 함유한 상품
                          // (같은 시설 제조는 빼고). 성분 이름만("우유")이면 넣지 않는다 — 우유를 사려는 검색에 과자를 섞지 않게
                          LabelRules.allergenOf(q) == null
                              ? Map.of("match_none", Map.of())
                              : Map.of(
                                  "term",
                                  Map.of(
                                      "allergens.contains",
                                      Map.of(
                                          "value",
                                          LabelRules.allergenOf(q),
                                          "boost",
                                          SearchRules.CONTENT_MATCH_BOOST)))),
                      "minimum_should_match",
                      1))));
      // 순위: 형태소 일치 > 앞부분 일치 > 조각 일치. 검색어 낱말이 그 순서로 붙어 있으면(사이 1낱말까지) 가산 — 구문 검색
      // ("콜라 제로" → "콜라 제로 500ml" 이 "제로 … 콜라맛"보다 위, 2026-11-05). 찾는 조건은 바꾸지 않는다
      bool.put(
          "should",
          List.of(
              Map.of("match", Map.of("name", Map.of("query", q, "boost", 3))),
              Map.of("match", Map.of("name.edge", Map.of("query", q, "boost", 2))),
              Map.of("match_phrase", Map.of("name", Map.of("query", q, "slop", 1, "boost", 2)))));
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
      // ADR-0072 — 순위 신호(storefront). 신호가 없으면 맨 뒤(missing _last), 같으면 다음 기준
      case POPULAR ->
          List.of(
              Map.of("salesWindow", Map.of("order", "desc", "missing", "_last")),
              Map.of("ratingScore", Map.of("order", "desc", "missing", "_last")),
              newest,
              tie);
      case RATING ->
          List.of(
              Map.of("ratingScore", Map.of("order", "desc", "missing", "_last")),
              Map.of("ratingCount", Map.of("order", "desc", "missing", "_last")),
              newest,
              tie);
      case REVIEW_COUNT ->
          List.of(
              Map.of("ratingCount", Map.of("order", "desc", "missing", "_last")),
              Map.of("ratingScore", Map.of("order", "desc", "missing", "_last")),
              newest,
              tie);
      // 0.9.0 BR-83 — ES 가 아니라 search() 가 할인 사본으로 줄 세운다. 여기서는 같은 할인율일 때를 위한 기본 순서만
      case DISCOUNT_RATE -> List.of(newest, tie);
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
        carried,
        s.hasNonNull("ratingAverage") ? s.get("ratingAverage").asDouble() : null,
        s.hasNonNull("ratingCount") ? s.get("ratingCount").asInt() : null,
        null,
        // products-v9(0.11.0) 표시사항 값 — 없으면 null(모름)
        s.hasNonNull("nutrition") ? s.get("nutrition") : null,
        s.hasNonNull("allergens") ? s.get("allergens") : null);
  }
}
