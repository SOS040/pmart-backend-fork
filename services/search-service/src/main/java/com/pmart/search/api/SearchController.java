package com.pmart.search.api;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.search.api.generated.SearchApi;
import com.pmart.search.api.generated.model.GetSearchFilters200Response;
import com.pmart.search.api.generated.model.GetSearchSuggestions200Response;
import com.pmart.search.api.generated.model.ListZeroResultKeywords200ResponseAsOf;
import com.pmart.search.api.generated.model.SearchHit;
import com.pmart.search.api.generated.model.SearchResult;
import com.pmart.search.api.generated.model.SearchResultPage;
import com.pmart.search.api.generated.model.SortKey;
import com.pmart.search.application.PopularKeywords;
import com.pmart.search.application.ProductSearch;
import com.pmart.search.application.SearchActivity;
import com.pmart.search.domain.SearchRules;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 SearchApi — 상품 검색·자동완성·필터. 업무 규칙은 application/domain 에 있고 변환만 한다. 승인 속성 필터(filters, "그룹.옵션")는
 * 색인의 filters(ProductVersionPublished.filterOptions)로 거르고, 실제로 적용한 값을 appliedFilters 로
 * 알린다(2026-11-04).
 */
@RestController
public class SearchController implements SearchApi {

  private final ProductSearch search;
  private final com.pmart.search.infrastructure.CategoryTreeClient categories;
  private final SearchActivity activity;
  private final PopularKeywords popular;
  private final Clock clock;
  private final com.pmart.search.application.CorrectedSearch corrected;
  private final com.pmart.search.application.ZeroResultKeywords zeroResults;

  public SearchController(
      ProductSearch search,
      com.pmart.search.infrastructure.CategoryTreeClient categories,
      SearchActivity activity,
      PopularKeywords popular,
      Clock clock,
      com.pmart.search.application.CorrectedSearch corrected,
      com.pmart.search.application.ZeroResultKeywords zeroResults) {
    this.corrected = corrected;
    this.zeroResults = zeroResults;
    this.search = search;
    this.categories = categories;
    this.activity = activity;
    this.popular = popular;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<SearchResult> searchProducts(
      @Nullable String q,
      @Nullable String categoryId,
      @Nullable String mfcId,
      SortKey sort,
      @Nullable List<String> filters,
      Boolean includeNotCarried,
      @Nullable Long minPrice,
      @Nullable Long maxPrice,
      Boolean noCorrection,
      Integer size,
      @Nullable String cursor) {
    ProductSearch.PriceRange price;
    try {
      price = new ProductSearch.PriceRange(minPrice, maxPrice);
    } catch (IllegalArgumentException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "최저 가격이 최고 가격보다 큽니다.");
    }
    com.pmart.search.application.CorrectedSearch.Outcome o;
    try {
      o =
          corrected.search(
              q,
              categoryId,
              mfcId,
              includeNotCarried == null || includeNotCarried,
              price,
              SearchRules.parseFilters(filters),
              SearchRules.sort(sort == null ? null : sort.getValue()),
              size,
              cursor,
              Boolean.TRUE.equals(noCorrection));
    } catch (SearchRules.InvalidCursorException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "잘못된 커서입니다. 처음부터 다시 조회하세요.");
    }
    ProductSearch.Result r = o.result();
    // 최근 검색어·인기 키워드 기록(첫 페이지·고객/비회원 토큰만, 비동기 — 실패해도 검색 응답은 그대로, 2026-11-17).
    // 결과 수는 원래 검색어의 것(0건 검색어 집계 — ADR-0073)
    activity.searched(PmartPrincipal.current().orElse(null), q, cursor, o.originalTotal());
    return ResponseEntity.ok(
        new SearchResult()
            .query(SearchRules.normalizeQuery(q))
            .items(r.items().stream().map(SearchController::hit).toList())
            .total((int) r.total())
            .page(new SearchResultPage().nextCursor(r.nextCursor()).size(r.items().size()))
            .appliedSort(SortKey.fromValue(r.appliedSort().name()))
            .correctedQuery(o.correctedQuery())
            .suggestedQuery(o.suggestedQuery())
            .appliedFilters(r.appliedFilters())
            .asOf(asOf()));
  }

  @Override
  public ResponseEntity<GetSearchSuggestions200Response> getSearchSuggestions(
      String q, Integer limit) {
    return ResponseEntity.ok(
        new GetSearchSuggestions200Response()
            .keywords(
                corrected.suggestions(q, limit == null ? 10 : limit)) // 후보가 없으면 고친 검색어로(ADR-0073)
            .popular(popular.current())); // 메모리 사본. 설정 없음·집계 실패면 빈 목록(2026-11-17)
  }

  @Override
  public ResponseEntity<GetSearchFilters200Response> getSearchFilters(
      @Nullable String q, @Nullable String categoryId, @Nullable String mfcId) {
    // 색인 집계(현재 조건의 건수) + product-service 필터 패널의 이름·선택 방식·옵션 순서(그 카테고리와 조상, 없으면 최상위 노드들)
    var facets = search.facets(q, categoryId);
    var groups = categories.filterGroupsFor(categoryId);
    java.util.Map<String, java.util.List<ProductSearch.Facet>> byKey =
        new java.util.LinkedHashMap<>();
    for (var g : groups) {
      byKey.put(g.id(), new java.util.ArrayList<>());
    }
    for (var f : facets) {
      byKey.computeIfAbsent(f.key(), k -> new java.util.ArrayList<>()).add(f);
    }
    List<com.pmart.search.api.generated.model.FilterAttribute> attributes =
        new java.util.ArrayList<>();
    byKey.forEach(
        (key, fs) -> {
          var group = groups.stream().filter(g -> g.id().equals(key)).findFirst().orElse(null);
          List<String> order = group == null ? List.of() : group.options();
          var values =
              fs.stream()
                  .sorted(
                      java.util.Comparator.comparingInt(
                          (ProductSearch.Facet f) -> {
                            int i = order.indexOf(f.value());
                            return i < 0 ? Integer.MAX_VALUE : i;
                          }))
                  .map(
                      f ->
                          new com.pmart.search.api.generated.model.FilterAttributeValuesInner()
                              .value(f.value())
                              .label(f.value())
                              .count((int) f.count()))
                  .toList();
          if (!values.isEmpty()) {
            attributes.add(
                new com.pmart.search.api.generated.model.FilterAttribute()
                    .key(key)
                    .label(group == null ? key : group.name())
                    .multiSelect(group == null || group.multiSelect())
                    .values(values));
          }
        });
    return ResponseEntity.ok(new GetSearchFilters200Response().attributes(attributes).asOf(asOf()));
  }

  private static SearchHit hit(ProductSearch.Hit h) {
    return new SearchHit()
        .productId(h.productId())
        .name(h.name())
        .imageUrl(h.imageUrl() == null ? null : URI.create(h.imageUrl()))
        .categoryPath(h.categoryPath()) // 대표 카테고리 이름 경로(product-service 트리 사본). 모르면 빈 목록
        .representativeSku(h.representativeSku())
        .basePrice(h.basePrice() == null ? null : Math.toIntExact(h.basePrice()))
        .priceVersion(h.priceVersion())
        .saleState(SearchHit.SaleStateEnum.fromValue(h.saleState()))
        .carried(h.carried())
        // ADR-0072 — 순위 신호 사본(리뷰 없으면 비움)
        .rating(h.ratingAverage() == null ? null : java.math.BigDecimal.valueOf(h.ratingAverage()))
        .reviewCount(h.ratingCount())
        .productVersion(h.productVersion());
  }

  private ListZeroResultKeywords200ResponseAsOf asOf() {
    return new ListZeroResultKeywords200ResponseAsOf()
        .asOf(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
        .freshness(ListZeroResultKeywords200ResponseAsOf.FreshnessEnum.CURRENT);
  }

  /** 결과 0건 검색어(ADR-0073) — MD·OPERATOR_ADMIN. */
  @Override
  public ResponseEntity<com.pmart.search.api.generated.model.ListZeroResultKeywords200Response>
      listZeroResultKeywords(Integer days, Integer size) {
    PmartPrincipal who = PmartPrincipal.current().orElse(null);
    if (who == null || !(who.hasRole("MD") || who.hasRole("OPERATOR_ADMIN"))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "상품 담당(MD)·운영 관리자만 볼 수 있습니다.");
    }
    var rows = zeroResults.list(days, size == null ? 50 : size);
    return ResponseEntity.ok(
        new com.pmart.search.api.generated.model.ListZeroResultKeywords200Response()
            .days(days)
            .items(
                rows.stream()
                    .map(
                        x ->
                            new com.pmart.search.api.generated.model
                                    .ListZeroResultKeywords200ResponseItemsInner()
                                .keyword(x.keyword())
                                .searches((int) x.searches())
                                .searchers((int) x.searchers())
                                .lastSearchedAt(
                                    x.lastSearchedAt() == null
                                        ? null
                                        : x.lastSearchedAt().atOffset(java.time.ZoneOffset.UTC)))
                    .toList())
            .asOf(asOf()));
  }
}
