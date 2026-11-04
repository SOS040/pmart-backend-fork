package com.pmart.search.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.search.api.generated.SearchApi;
import com.pmart.search.api.generated.model.GetSearchFilters200Response;
import com.pmart.search.api.generated.model.GetSearchFilters200ResponseAsOf;
import com.pmart.search.api.generated.model.GetSearchSuggestions200Response;
import com.pmart.search.api.generated.model.SearchHit;
import com.pmart.search.api.generated.model.SearchResult;
import com.pmart.search.api.generated.model.SearchResultPage;
import com.pmart.search.api.generated.model.SortKey;
import com.pmart.search.application.ProductSearch;
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
  private final Clock clock;

  public SearchController(
      ProductSearch search,
      com.pmart.search.infrastructure.CategoryTreeClient categories,
      Clock clock) {
    this.search = search;
    this.categories = categories;
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
      Integer size,
      @Nullable String cursor) {
    ProductSearch.PriceRange price;
    try {
      price = new ProductSearch.PriceRange(minPrice, maxPrice);
    } catch (IllegalArgumentException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "최저 가격이 최고 가격보다 큽니다.");
    }
    ProductSearch.Result r;
    try {
      r =
          search.search(
              q,
              categoryId,
              mfcId,
              includeNotCarried == null || includeNotCarried,
              price,
              SearchRules.parseFilters(filters),
              SearchRules.sort(sort == null ? null : sort.getValue()),
              size,
              cursor);
    } catch (SearchRules.InvalidCursorException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "잘못된 커서입니다. 처음부터 다시 조회하세요.");
    }
    return ResponseEntity.ok(
        new SearchResult()
            .query(SearchRules.normalizeQuery(q))
            .items(r.items().stream().map(SearchController::hit).toList())
            .total((int) r.total())
            .page(new SearchResultPage().nextCursor(r.nextCursor()).size(r.items().size()))
            .appliedSort(SortKey.fromValue(r.appliedSort().name()))
            .appliedFilters(r.appliedFilters())
            .asOf(asOf()));
  }

  @Override
  public ResponseEntity<GetSearchSuggestions200Response> getSearchSuggestions(
      String q, Integer limit) {
    return ResponseEntity.ok(
        new GetSearchSuggestions200Response()
            .keywords(search.suggestions(q, limit == null ? 10 : limit))
            .popular(List.of()));
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
        .productVersion(h.productVersion());
  }

  private GetSearchFilters200ResponseAsOf asOf() {
    return new GetSearchFilters200ResponseAsOf()
        .asOf(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
        .freshness(GetSearchFilters200ResponseAsOf.FreshnessEnum.CURRENT);
  }
}
