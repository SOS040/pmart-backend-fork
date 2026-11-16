package com.pmart.search.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 보존 기간이 지난 최근 검색어(retention)·검색 기록(인기 키워드 집계 기간 + 1일)을 지운다(2026-11-17). 여러 인스턴스가 같이 돌아도 같은
 * 결과다(조건 삭제, 버전 충돌은 건너뜀). 실패해도 다음 날 다시 한다 — 조회는 기간 조건으로 거르므로 늦게 지워져도 보이지 않는다.
 */
@Component
class SearchHousekeeping {

  private static final Logger log = LoggerFactory.getLogger(SearchHousekeeping.class);

  private final RecentSearches recent;
  private final PopularKeywords popular;

  SearchHousekeeping(RecentSearches recent, PopularKeywords popular) {
    this.recent = recent;
    this.popular = popular;
  }

  @Scheduled(cron = "0 17 4 * * *", zone = "Asia/Seoul")
  void purge() {
    try {
      log.info("최근 검색어 보존 기간 정리 {}건", recent.purgeExpired());
    } catch (RuntimeException e) {
      log.warn("최근 검색어 정리 실패: {}", e.getClass().getSimpleName());
    }
    try {
      log.info("검색 기록 보존 기간 정리 {}건", popular.purgeExpired());
    } catch (RuntimeException e) {
      log.warn("검색 기록 정리 실패: {}", e.getClass().getSimpleName());
    }
  }
}
