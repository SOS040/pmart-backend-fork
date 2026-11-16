package com.pmart.search.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.search.domain.SearchKeywords;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 검색 한 번의 기록(사용자 결정 2026-11-17). 첫 페이지(cursor 없음)이고 검색어가 있을 때만:
 *
 * <ul>
 *   <li>고객(CUSTOMER) 토큰 → 최근 검색어({@link RecentSearches})
 *   <li>고객·비회원(GUEST) 토큰 → 인기 키워드 집계용 검색 기록({@link PopularKeywords}). 토큰 없는 익명 검색은 세지 않는다
 * </ul>
 *
 * 검색 응답과 따로 작은 작업 큐에서 비동기로 남긴다 — 검색엔진 쓰기가 느리거나 실패해도 검색은 늦어지거나 실패하지 않는다. 실패는 WARN(검색어·검색자 없이 예외
 * 종류만), 큐가 차면 버린다. 검색자는 해시로만 넘긴다.
 */
@Service
public class SearchActivity {

  private static final Logger log = LoggerFactory.getLogger(SearchActivity.class);

  private final RecentSearches recent;
  private final PopularKeywords popular;

  /**
   * 기록 작업 큐 — 작업자 2, 대기 1000, 가득 차면 거절(버리고 WARN). 빈으로 두지 않는다 — Executor 빈이 있으면 Spring Boot 의 기본 작업
   * 실행기 자동구성이 물러난다.
   */
  private final ExecutorService executor;

  private final Clock clock;
  private final AtomicLong dropped = new AtomicLong();

  private final SearchProperties props;
  private final java.util.concurrent.atomic.AtomicBoolean warnedNoKey =
      new java.util.concurrent.atomic.AtomicBoolean();

  public SearchActivity(
      RecentSearches recent, PopularKeywords popular, Clock clock, SearchProperties props) {
    this.props = props;
    this.recent = recent;
    this.popular = popular;
    this.clock = clock;
    AtomicInteger n = new AtomicInteger();
    this.executor =
        new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000),
            r -> {
              Thread t = new Thread(r, "search-activity-" + n.incrementAndGet());
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }

  /** 검색 API 가 결과를 만든 뒤 부른다. 예외를 던지지 않는다. */
  public void searched(
      @Nullable PmartPrincipal who,
      @Nullable String query,
      @Nullable String cursor,
      long resultCount) {
    try {
      if (who == null || (cursor != null && !cursor.isBlank())) {
        return;
      }
      boolean customer = who.type() == PmartPrincipal.PrincipalType.CUSTOMER;
      boolean guest = who.type() == PmartPrincipal.PrincipalType.GUEST;
      if (!customer && !guest) {
        return;
      }
      String keyword = SearchKeywords.normalize(query);
      if (keyword == null) {
        return;
      }
      String key = props.searcherHashKey();
      if (key == null || key.isBlank()) {
        if (warnedNoKey.compareAndSet(false, true)) {
          log.warn("pmart.search.searcher-hash-key 가 없어 검색 기록(최근·인기)을 하지 않습니다");
        }
        return;
      }
      String searcher = SearchKeywords.searcherHash(key, who.type().name(), who.subject());
      Instant at = clock.instant();
      executor.execute(() -> record(searcher, keyword, resultCount, at, customer));
    } catch (RejectedExecutionException e) {
      long n = dropped.incrementAndGet();
      if (n == 1 || n % 100 == 0) {
        log.warn("검색 기록 큐가 가득 차 버림(누적 {}건)", n);
      }
    } catch (RuntimeException e) {
      log.warn("검색 기록 준비 실패: {}", e.getClass().getSimpleName());
    }
  }

  private void record(
      String searcher, String keyword, long resultCount, Instant at, boolean customer) {
    try {
      popular.count(searcher, keyword, resultCount, at);
    } catch (RuntimeException e) {
      log.warn("검색 기록(인기 키워드) 실패: {}", e.getClass().getSimpleName());
    }
    if (customer) {
      try {
        recent.remember(searcher, keyword, at);
      } catch (RuntimeException e) {
        log.warn("최근 검색어 기록 실패: {}", e.getClass().getSimpleName());
      }
    }
  }
}
