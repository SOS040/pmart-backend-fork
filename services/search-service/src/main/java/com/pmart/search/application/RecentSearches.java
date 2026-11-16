package com.pmart.search.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.search.domain.SearchKeywords;
import com.pmart.search.infrastructure.SearchIndex;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 고객 최근 검색어(사용자 결정 2026-11-17, Commerce-business-logic.md §6.7). 색인 {@code search-recent} 에 검색자 해시
 * + 검색어당 문서 하나 — 같은 검색어는 덮어써 맨 위로 올린다. 최근 {@code max-items} 개만, {@code retention} 이 지난 것은 보이지 않고 매일
 * 지운다. 삭제(개별·전체)는 이 목록만 바꾼다(인기 키워드용 검색 기록은 그대로).
 *
 * <p>고객(CUSTOMER) 토큰만 — 그 밖의 종류는 403(비회원은 보안 체인이 먼저 막는다). 검색자 원래 ID 는 색인에 넣지 않는다.
 */
@Service
public class RecentSearches {

  private static final Logger log = LoggerFactory.getLogger(RecentSearches.class);

  /** 최근 검색어 한 줄. */
  public record Entry(String keyword, Instant searchedAt) {}

  private final SearchIndex index;
  private final SearchProperties props;
  private final Clock clock;
  private final AtomicBoolean warnedUnset = new AtomicBoolean();

  public RecentSearches(SearchIndex index, SearchProperties props, Clock clock) {
    this.index = index;
    this.props = props;
    this.clock = clock;
  }

  /** 본인 최근 검색어, 최신순. 정책값이 없으면 CONFIG_REQUIRED. */
  public List<Entry> list(@Nullable PmartPrincipal who) {
    String searcher = customerHash(who);
    SearchProperties.Recent policy = policyOrThrow();
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("size", policy.maxItems());
    req.put("_source", List.of("keyword", "searchedAt"));
    req.put(
        "query",
        Map.of(
            "bool",
            Map.of(
                "filter",
                List.of(
                    Map.of("term", Map.of("searcher", searcher)),
                    Map.of(
                        "range",
                        Map.of(
                            "searchedAt",
                            Map.of(
                                "gte", clock.instant().minus(policy.retention()).toString())))))));
    req.put("sort", List.of(Map.of("searchedAt", Map.of("order", "desc"))));
    JsonNode res = index.search(SearchIndex.RECENT_SEARCHES, req);
    List<Entry> out = new ArrayList<>();
    for (JsonNode h : res.path("hits").path("hits")) {
      JsonNode s = h.path("_source");
      out.add(
          new Entry(s.path("keyword").asString(), Instant.parse(s.path("searchedAt").asString())));
    }
    return List.copyOf(out);
  }

  /** 개별 삭제 — 없어도 성공(멱등). 검색어는 기록과 같은 모양으로 맞춘다. */
  public void delete(@Nullable PmartPrincipal who, @Nullable String keyword) {
    String searcher = customerHash(who);
    String k = SearchKeywords.normalize(keyword);
    if (k == null) {
      return;
    }
    index.delete(SearchIndex.RECENT_SEARCHES, SearchKeywords.recentDocId(searcher, k));
  }

  /** 전체 삭제 — 없어도 성공(멱등). */
  public void clear(@Nullable PmartPrincipal who) {
    String searcher = customerHash(who);
    index.deleteByQuery(SearchIndex.RECENT_SEARCHES, Map.of("term", Map.of("searcher", searcher)));
  }

  /**
   * 검색 한 번을 남긴다(SearchActivity 가 비동기로 부름). 같은 검색어는 덮어써 맨 위로 올리고, 이 검색자의 다른 문서 중 최신 {@code max-items
   * - 1} 개 밖은 지운다. 정책값이 없으면 남기지 않는다(경고 한 번).
   */
  void remember(String searcherHash, String keyword, Instant at) {
    SearchProperties.Recent policy = props.recent();
    if (policy == null || !policy.complete()) {
      if (warnedUnset.compareAndSet(false, true)) {
        log.warn("pmart.search.recent(max-items·retention) 설정이 없어 최근 검색어를 남기지 않는다");
      }
      return;
    }
    String id = SearchKeywords.recentDocId(searcherHash, keyword);
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("searcher", searcherHash);
    doc.put("keyword", keyword);
    doc.put("searchedAt", at.toString());
    // 같은 검색어를 잇달아 검색해 쓰기가 뒤바뀌어 도착해도 더 새 시각이 남는다(외부 버전 = 검색 시각 밀리초)
    if (index.putIfNewer(SearchIndex.RECENT_SEARCHES, id, at.toEpochMilli(), doc)) {
      trim(searcherHash, id, policy.maxItems());
    }
  }

  /**
   * 방금 쓴 문서를 빼고 최신 {@code maxItems - 1} 개 밖을 지운다 — 방금 쓴 문서가 아직 새로 고침 전이어도 정확하다. 동시에 검색해 한 번 덜 지워져도
   * 조회는 {@code maxItems} 개만 보이고 다음 기록 때 정리된다.
   */
  private void trim(String searcherHash, String keptId, int maxItems) {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("from", maxItems - 1);
    req.put("size", 100);
    req.put("_source", false);
    req.put(
        "query",
        Map.of(
            "bool",
            Map.of(
                "filter",
                List.of(Map.of("term", Map.of("searcher", searcherHash))),
                "must_not",
                List.of(Map.of("ids", Map.of("values", List.of(keptId)))))));
    req.put("sort", List.of(Map.of("searchedAt", Map.of("order", "desc"))));
    JsonNode res = index.search(SearchIndex.RECENT_SEARCHES, req);
    List<String> extra = new ArrayList<>();
    for (JsonNode h : res.path("hits").path("hits")) {
      extra.add(h.path("_id").asString());
    }
    if (!extra.isEmpty()) {
      index.deleteByQuery(SearchIndex.RECENT_SEARCHES, Map.of("ids", Map.of("values", extra)));
    }
  }

  /** 보존 기간이 지난 최근 검색어를 지운다(매일). 정책값이 없으면 하지 않는다. 지운 건수, 하지 않았으면 -1. */
  public long purgeExpired() {
    SearchProperties.Recent policy = props.recent();
    if (policy == null || !policy.complete()) {
      return -1;
    }
    return index.deleteByQuery(
        SearchIndex.RECENT_SEARCHES,
        Map.of(
            "range",
            Map.of(
                "searchedAt", Map.of("lt", clock.instant().minus(policy.retention()).toString()))));
  }

  private SearchProperties.Recent policyOrThrow() {
    SearchProperties.Recent policy = props.recent();
    if (policy == null || !policy.complete()) {
      throw new PmartException(
          ErrorCode.CONFIG_REQUIRED, "최근 검색어 정책(pmart.search.recent)이 설정되지 않았습니다.");
    }
    return policy;
  }

  /** 고객 토큰이면 검색자 해시, 아니면 403. */
  private String customerHash(@Nullable PmartPrincipal who) {
    if (who == null || who.type() != PmartPrincipal.PrincipalType.CUSTOMER) {
      throw new PmartException(ErrorCode.FORBIDDEN, "고객만 최근 검색어를 쓸 수 있습니다.");
    }
    String key = props.searcherHashKey();
    if (key == null || key.isBlank()) {
      throw new PmartException(
          ErrorCode.CONFIG_REQUIRED, "검색자 해시 키(pmart.search.searcher-hash-key)가 설정되지 않았습니다.");
    }
    return SearchKeywords.searcherHash(key, who.type().name(), who.subject());
  }
}
