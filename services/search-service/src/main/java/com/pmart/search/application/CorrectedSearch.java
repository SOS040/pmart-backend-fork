package com.pmart.search.application;

import com.pmart.search.domain.SearchRules;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 오타 교정을 얹은 검색(2026-11-20 ADR-0073). 첫 쪽·검색어 있음·초성 아님·교정 끔이 아닐 때: 결과 0건이면 고친 검색어로 다시 찾아(결과가 있으면)
 * {@code correctedQuery}, 결과가 적으면(설정 fewResults 이하) 고친 검색어가 더 많이 찾을 때만 {@code suggestedQuery}.
 * 자동완성은 후보가 없으면 고친 검색어로. 교정이 실패하면(사전 없음 등) 교정 없이 — 검색을 실패시키지 않는다.
 */
@Service
public class CorrectedSearch {

  private static final Logger log = LoggerFactory.getLogger(CorrectedSearch.class);

  public record Outcome(
      ProductSearch.Result result,
      long originalTotal,
      @Nullable String correctedQuery,
      @Nullable String suggestedQuery) {}

  private final ProductSearch search;
  private final QueryCorrector corrector;
  private final SearchProperties props;

  public CorrectedSearch(ProductSearch search, QueryCorrector corrector, SearchProperties props) {
    this.search = search;
    this.corrector = corrector;
    this.props = props;
  }

  public Outcome search(
      @Nullable String q,
      @Nullable String categoryId,
      @Nullable String mfcId,
      boolean includeNotCarried,
      ProductSearch.PriceRange price,
      Map<String, List<String>> filters,
      boolean onSale,
      SearchRules.Sort sort,
      int size,
      @Nullable String cursor,
      boolean noCorrection) {
    ProductSearch.Result r =
        search.search(
            q, categoryId, mfcId, includeNotCarried, price, filters, onSale, sort, size, cursor);
    String query = SearchRules.normalizeQuery(q);
    SearchProperties.Correction cfg = props.correction();
    if (noCorrection
        || query == null
        || SearchRules.isChosungQuery(query)
        || cfg == null
        || !cfg.complete()
        || r.total() > cfg.fewResults()) {
      return new Outcome(r, r.total(), null, null);
    }
    if (cursor != null) {
      // 다음 쪽(2026-11-20 리뷰 지적 2): 원래 검색어가 0건이면 첫 쪽은 고친 검색어 결과였다 — 같은 원래 검색어 + 커서로 오면 고친 검색어로
      // 이어 준다(전에는 커서가 있으면 교정을 건너뛰어 빈 쪽). 결과가 적어 제안만 한 경우는 원래 검색어 결과라 그대로
      if (r.total() > 0) {
        return new Outcome(r, r.total(), null, null);
      }
      Optional<String> next = correct(query);
      if (next.isEmpty()) {
        return new Outcome(r, 0, null, null);
      }
      ProductSearch.Result page =
          search.search(
              next.get(),
              categoryId,
              mfcId,
              includeNotCarried,
              price,
              filters,
              onSale,
              sort,
              size,
              cursor);
      return page.total() > 0
          ? new Outcome(page, 0, next.get(), null)
          : new Outcome(r, 0, null, null);
    }
    Optional<String> fixed = correct(query);
    if (fixed.isEmpty()) {
      return new Outcome(r, r.total(), null, null);
    }
    ProductSearch.Result again =
        search.search(
            fixed.get(),
            categoryId,
            mfcId,
            includeNotCarried,
            price,
            filters,
            onSale,
            sort,
            size,
            null);
    if (r.total() == 0) {
      return again.total() > 0
          ? new Outcome(again, 0, fixed.get(), null)
          : new Outcome(r, 0, null, null);
    }
    // 0.12.0 BR-88 — 원래 검색어가 상품명에 그대로 맞으면(정상 이름) 결과가 적어도 제안하지 않는다
    if (again.total() <= r.total() || search.nameMatches(query)) {
      return new Outcome(r, r.total(), null, null);
    }
    return new Outcome(r, r.total(), null, fixed.get());
  }

  public List<String> suggestions(String q, int limit) {
    List<String> out = search.suggestions(q, limit);
    SearchProperties.Correction cfg = props.correction();
    String query = SearchRules.normalizeQuery(q);
    if (!out.isEmpty() || cfg == null || !cfg.complete() || query == null) {
      return out;
    }
    return correct(query).map(f -> search.suggestions(f, limit)).orElse(out);
  }

  private Optional<String> correct(String query) {
    try {
      return corrector.correct(query);
    } catch (RuntimeException e) {
      // 검색어는 로그에 남기지 않는다(검색 기록 규칙) — 원인 종류만
      log.warn(
          "오타 교정 건너뜀: {} {}",
          e.getClass().getSimpleName(),
          e.getMessage() == null
              ? ""
              : e.getMessage()
                  .replaceAll("\s+", " ")
                  .substring(0, Math.min(200, e.getMessage().length())));
      return Optional.empty();
    }
  }
}
