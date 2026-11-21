package com.pmart.auth.application;

import com.pmart.auth.infrastructure.NaverProfileEmailStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 네이버 교환 이메일 보관 만료 파기(2026-11-21 BR-77 — 교환 + 10분). 지운 수만 로그. 기본 10분마다. */
@Component
public class NaverProfileEmailPurger {

  private static final Logger log = LoggerFactory.getLogger(NaverProfileEmailPurger.class);
  private static final int BATCH = 500;

  private final NaverProfileEmailStore store;
  private final Clock clock;

  public NaverProfileEmailPurger(NaverProfileEmailStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Scheduled(
      initialDelayString = "${pmart.auth.naver-profile-email.purge-initial-delay:PT5M}",
      fixedDelayString = "${pmart.auth.naver-profile-email.purge-interval:PT10M}")
  public void scheduled() {
    purge();
  }

  public int purge() {
    int total = 0;
    int n;
    do {
      n = store.purgeExpired(clock.instant(), BATCH);
      total += n;
    } while (n == BATCH);
    if (total > 0) {
      log.info("네이버 교환 이메일 {}건 파기(보관 만료)", total);
    }
    return total;
  }
}
