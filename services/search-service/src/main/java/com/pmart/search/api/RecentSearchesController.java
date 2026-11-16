package com.pmart.search.api;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.search.api.generated.RecentSearchesApi;
import com.pmart.search.api.generated.model.ListRecentSearches200Response;
import com.pmart.search.api.generated.model.ListRecentSearches200ResponseItemsInner;
import com.pmart.search.application.RecentSearches;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 RecentSearchesApi — 본인 최근 검색어(2026-11-17). 고객 확인·규칙은 {@link RecentSearches}. 삭제는 본디 멱등이라(없어도
 * 204) Idempotency-Key 를 따로 저장하지 않는다(이 서비스는 DB 가 없다).
 */
@RestController
public class RecentSearchesController implements RecentSearchesApi {

  private final RecentSearches recent;

  public RecentSearchesController(RecentSearches recent) {
    this.recent = recent;
  }

  @Override
  public ResponseEntity<ListRecentSearches200Response> listRecentSearches() {
    var items =
        recent.list(PmartPrincipal.current().orElse(null)).stream()
            .map(
                e ->
                    new ListRecentSearches200ResponseItemsInner()
                        .keyword(e.keyword())
                        .searchedAt(e.searchedAt().atOffset(ZoneOffset.UTC)))
            .toList();
    return ResponseEntity.ok(new ListRecentSearches200Response().items(items));
  }

  @Override
  public ResponseEntity<Void> clearRecentSearches(String idempotencyKey, String xSourceSystem) {
    recent.clear(PmartPrincipal.current().orElse(null));
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> deleteRecentSearch(
      String keyword, String idempotencyKey, String xSourceSystem) {
    recent.delete(PmartPrincipal.current().orElse(null), keyword);
    return ResponseEntity.noContent().build();
  }
}
