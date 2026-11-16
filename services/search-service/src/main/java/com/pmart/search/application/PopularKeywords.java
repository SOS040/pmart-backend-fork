package com.pmart.search.application;

import com.pmart.search.domain.SearchKeywords;
import com.pmart.search.infrastructure.SearchIndex;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 인기 키워드(사용자 결정 2026-11-17, Commerce-business-logic.md §6.9). 검색 기록 색인 {@code search-log}(센 검색 하나당
 * 문서 하나)에서 최근 {@code window} 동안 결과가 1건 이상이었던 검색을 검색어별로 세고, 서로 다른 검색자 {@code min-searchers} 명 이상인
 * 검색어만 검색 횟수 순 {@code size} 개. 숨김 목록({@code hidden-keywords})은 뺀다. 고객·비회원 토큰 검색만 센다(익명 제외).
 *
 * <p>결과는 메모리에 {@code refresh} 동안 두어 자동완성이 빠르게 돌려준다. 설정이 없거나 집계에 실패하면 빈 목록(또는 이전 사본) — 자동완성과 검색은
 * 실패하지 않는다.
 */
@Service
public class PopularKeywords {

  private static final Logger log = LoggerFactory.getLogger(PopularKeywords.class);

  /** 집계에서 검색어 후보를 몇 개까지 읽을지(숨김·검색자 수로 빠지는 것을 감안). */
  private static final int CANDIDATES = 500;

  private record Snapshot(List<String> keywords, Instant computedAt) {}

  private final SearchIndex index;
  private final SearchProperties props;
  private final Clock clock;
  private final ReentrantLock computing = new ReentrantLock();
  private final AtomicBoolean warnedUnset = new AtomicBoolean();
  private volatile @Nullable Snapshot snapshot;

  public PopularKeywords(SearchIndex index, SearchProperties props, Clock clock) {
    this.index = index;
    this.props = props;
    this.clock = clock;
  }

  /** 지금 인기 키워드. 예외를 던지지 않는다. */
  public List<String> current() {
    SearchProperties.Popular policy = policy();
    if (policy == null) {
      return List.of();
    }
    Snapshot s = snapshot;
    Instant now = clock.instant();
    if (s != null && s.computedAt().plus(policy.refresh()).isAfter(now)) {
      return s.keywords();
    }
    // 한 요청만 다시 계산하고, 나머지는 이전 사본(없으면 빈 목록)을 바로 돌려준다
    if (!computing.tryLock()) {
      return s == null ? List.of() : s.keywords();
    }
    try {
      return refresh(policy, now, s);
    } finally {
      computing.unlock();
    }
  }

  /** 사본을 바로 다시 계산한다(시험·운영 확인용). */
  public List<String> refreshNow() {
    SearchProperties.Popular policy = policy();
    if (policy == null) {
      return List.of();
    }
    computing.lock();
    try {
      return refresh(policy, clock.instant(), snapshot);
    } finally {
      computing.unlock();
    }
  }

  private List<String> refresh(SearchProperties.Popular policy, Instant now, @Nullable Snapshot s) {
    List<String> keywords;
    try {
      keywords = compute(policy, now);
    } catch (RuntimeException e) {
      // 다음 refresh 까지 다시 부르지 않는다(검색엔진이 느릴 때 자동완성마다 기다리지 않게)
      log.warn("인기 키워드 집계 실패 — 이전 사본을 쓴다: {}", e.getClass().getSimpleName());
      keywords = s == null ? List.of() : s.keywords();
    }
    snapshot = new Snapshot(keywords, now);
    return keywords;
  }

  private List<String> compute(SearchProperties.Popular policy, Instant now) {
    Map<String, Object> terms = new LinkedHashMap<>();
    terms.put("field", "keyword");
    terms.put("size", CANDIDATES);
    terms.put("order", List.of(Map.of("_count", "desc"), Map.of("_key", "asc")));
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("size", 0);
    req.put(
        "query",
        Map.of(
            "bool",
            Map.of(
                "filter",
                List.of(
                    Map.of(
                        "range",
                        Map.of("at", Map.of("gte", now.minus(policy.window()).toString()))),
                    Map.of("range", Map.of("resultCount", Map.of("gte", 1)))))));
    req.put(
        "aggs",
        Map.of(
            "k",
            Map.of(
                "terms",
                terms,
                "aggs",
                Map.of(
                    "searchers",
                    Map.of(
                        "cardinality",
                        Map.of("field", "searcher", "precision_threshold", 3000))))));
    JsonNode res = index.search(SearchIndex.SEARCH_LOG, req);
    List<SearchKeywords.KeywordCount> counted = new ArrayList<>();
    for (JsonNode b : res.path("aggregations").path("k").path("buckets")) {
      counted.add(
          new SearchKeywords.KeywordCount(
              b.path("key").asString(),
              b.path("doc_count").asLong(),
              b.path("searchers").path("value").asLong()));
    }
    return SearchKeywords.popular(
        counted, policy.size(), policy.minSearchers(), policy.hiddenKeywords());
  }

  /**
   * 센 검색 하나를 남긴다(SearchActivity 가 비동기로 부름). 결과 0건도 남기고 집계 때 거른다. 정책값이 없으면 남기지 않는다 — 보존 기간을 몰라 지울 수
   * 없으므로.
   */
  void count(String searcherHash, String keyword, long resultCount, Instant at) {
    SearchProperties.Popular p = props.popular();
    if (p == null || !p.complete()) {
      return;
    }
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("keyword", keyword);
    doc.put("searcher", searcherHash);
    doc.put("at", at.toString());
    doc.put("resultCount", resultCount);
    index.put(SearchIndex.SEARCH_LOG, UUID.randomUUID().toString(), doc);
  }

  /** 집계 기간 + 1일이 지난 검색 기록을 지운다(매일). 정책값이 없으면 하지 않는다. 지운 건수, 하지 않았으면 -1. */
  public long purgeExpired() {
    SearchProperties.Popular p = props.popular();
    if (p == null || p.window() == null || p.window().compareTo(Duration.ZERO) <= 0) {
      return -1;
    }
    Instant before = clock.instant().minus(p.window()).minus(Duration.ofDays(1));
    return index.deleteByQuery(
        SearchIndex.SEARCH_LOG, Map.of("range", Map.of("at", Map.of("lt", before.toString()))));
  }

  /** 정책값이 모두 있으면 그 정책, 없으면 null(경고 한 번). */
  private SearchProperties.@Nullable Popular policy() {
    SearchProperties.Popular p = props.popular();
    if (p == null || !p.complete()) {
      if (warnedUnset.compareAndSet(false, true)) {
        log.warn(
            "pmart.search.popular(window·size·min-searchers·hidden-keywords·refresh) 설정이 없어 인기 키워드는 빈 목록이다");
      }
      return null;
    }
    return p;
  }
}
