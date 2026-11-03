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
 * 계약 SearchApi — 상품 검색·자동완성·필터. 업무 규칙은 application/domain 에 있고 변환만 한다. 승인 속성 필터(filters)는 원천(승인
 * 속성)이 아직 없어 적용하지 않고 appliedFilters 를 비워 알린다.
 */
@RestController
public class SearchController implements SearchApi {

  private final ProductSearch search;
  private final Clock clock;

  public SearchController(ProductSearch search, Clock clock) {
    this.search = search;
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
            .appliedFilters(List.of())
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
    // 승인 속성(용량·개당 수량 등) 원천이 아직 없다 → 빈 목록(0건이 아니라 "필터 없음")
    return ResponseEntity.ok(new GetSearchFilters200Response().attributes(List.of()).asOf(asOf()));
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
