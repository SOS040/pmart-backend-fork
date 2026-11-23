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

  /** 색인 사본(JsonNode) → 생성 모델. 상태 없는 변환만. */
  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  private final ProductSearch search;
  private final com.pmart.search.infrastructure.CategoryTreeClient categories;
  private final SearchActivity activity;
  private final PopularKeywords popular;
  private final Clock clock;
  private final com.pmart.search.application.CorrectedSearch corrected;
  private final com.pmart.search.application.ZeroResultKeywords zeroResults;
  private final com.pmart.search.application.ProductAvailability availability;

  public SearchController(
      ProductSearch search,
      com.pmart.search.infrastructure.CategoryTreeClient categories,
      SearchActivity activity,
      PopularKeywords popular,
      Clock clock,
      com.pmart.search.application.CorrectedSearch corrected,
      com.pmart.search.application.ZeroResultKeywords zeroResults,
      com.pmart.search.application.ProductAvailability availability) {
    this.availability = availability;
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
      Boolean onSale,
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
              Boolean.TRUE.equals(onSale), // 0.8.0 BR-82 할인 중인 상품만
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
    // 0.10.0 목록 판매 가능 여부 — mfcId 를 줬을 때만, 실패는 UNKNOWN(ProductAvailability)
    java.util.Map<String, String> avail =
        availability.of(r.items().stream().map(ProductSearch.Hit::productId).toList(), mfcId);
    return ResponseEntity.ok(
        new SearchResult()
            .query(SearchRules.normalizeQuery(q))
            .items(
                r.items().stream()
                    .map(
                        h ->
                            hit(h)
                                .availability(
                                    avail == null
                                        ? null
                                        : SearchHit.AvailabilityEnum.fromValue(
                                            avail.getOrDefault(
                                                h.productId(),
                                                com.pmart.search.domain.ListAvailability.UNKNOWN))))
                    .toList())
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
      @Nullable String q, Integer limit) {
    // q 없음·공백뿐 = 검색 첫 화면 — 인기 키워드만(0.5.2, 2026-11-20)
    List<String> keywords =
        SearchRules.normalizeQuery(q) == null
            ? List.of()
            : corrected.suggestions(q, limit == null ? 10 : limit); // 후보가 없으면 고친 검색어로(ADR-0073)
    return ResponseEntity.ok(
        new GetSearchSuggestions200Response()
            .keywords(keywords)
            .popular(popular.current())); // 메모리 사본. 설정 없음·집계 실패면 빈 목록(2026-11-17)
  }

  @Override
  public ResponseEntity<GetSearchFilters200Response> getSearchFilters(
      @Nullable String q, @Nullable String categoryId, @Nullable String mfcId, Boolean onSale) {
    // 색인 집계(현재 조건의 건수) + product-service 필터 패널의 이름·선택 방식·옵션 순서(그 카테고리와 조상, 없으면 최상위 노드들)
    var facets = search.facets(q, categoryId, Boolean.TRUE.equals(onSale)); // 0.9.0 BR-83
    // 이름은 가까운 패널 → 모든 패널, 같은 이름 그룹은 한 줄(n:<이름>) — 2026-11-22 BR-81
    List<com.pmart.search.api.generated.model.FilterAttribute> attributes =
        com.pmart.search.application.FilterAttributes.build(
                facets,
                categories.filterGroupsFor(categoryId),
                facets.isEmpty() ? java.util.Map.of() : categories.allFilterGroups())
            .stream()
            .map(
                a ->
                    new com.pmart.search.api.generated.model.FilterAttribute()
                        .key(a.key())
                        .label(a.label())
                        .multiSelect(a.multiSelect())
                        .values(
                            a.values().stream()
                                .map(
                                    v ->
                                        new com.pmart.search.api.generated.model
                                                .FilterAttributeValuesInner()
                                            .value(v.value())
                                            .label(v.value())
                                            .count((int) v.count()))
                                .toList()))
            .toList();
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
        // 기간 할인(0.x ADR-0082 4단계) — 가격 사본과 같은 버전·끝 전일 때만(ProductSearch)
        .salePrice(h.discount() == null ? null : Math.toIntExact(h.discount().salePrice()))
        .discountRatePercent(h.discount() == null ? null : h.discount().ratePercent())
        .discountName(h.discount() == null ? null : h.discount().name())
        .discountEndsAt(
            h.discount() == null || h.discount().endsAt() == null
                ? null
                : OffsetDateTime.ofInstant(h.discount().endsAt(), ZoneOffset.UTC))
        .saleState(SearchHit.SaleStateEnum.fromValue(h.saleState()))
        .carried(h.carried())
        // ADR-0072 — 순위 신호 사본(리뷰 없으면 비움)
        .rating(h.ratingAverage() == null ? null : java.math.BigDecimal.valueOf(h.ratingAverage()))
        .reviewCount(h.ratingCount())
        // products-v9(0.11.0) 표시사항 값 — 색인 사본 그대로(없으면 null)
        .nutrition(
            h.nutrition() == null
                ? null
                : JSON.convertValue(
                    h.nutrition(), com.pmart.search.api.generated.model.Nutrition.class))
        .allergens(
            h.allergens() == null
                ? null
                : JSON.convertValue(
                    h.allergens(), com.pmart.search.api.generated.model.Allergens.class))
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
