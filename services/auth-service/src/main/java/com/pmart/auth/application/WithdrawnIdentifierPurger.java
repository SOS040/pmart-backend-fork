package com.pmart.auth.application;

import com.pmart.auth.infrastructure.WithdrawnIdentifierStore;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 탈퇴한 소셜 식별값 만료 파기(2026-11-20 ADR-0077 결정 5 — 보관 탈퇴 완료 + 5년). {@code expires_at} 이 지난 행을 한 번에
 * {@code BATCH} 행씩 지운다. 지운 수만 로그(값 없음). 기본 하루 한 번({@code
 * pmart.auth.withdrawn-identifier.purge-interval}).
 */
@Component
public class WithdrawnIdentifierPurger {

  private static final Logger log = LoggerFactory.getLogger(WithdrawnIdentifierPurger.class);
  private static final int BATCH = 500;

  private final WithdrawnIdentifierStore store;
  private final TransactionTemplate tx;
  private final Clock clock;

  public WithdrawnIdentifierPurger(
      WithdrawnIdentifierStore store, TransactionTemplate tx, Clock clock) {
    this.store = store;
    this.tx = tx;
    this.clock = clock;
  }

  @Scheduled(
      initialDelayString = "${pmart.auth.withdrawn-identifier.purge-initial-delay:PT10M}",
      fixedDelayString = "${pmart.auth.withdrawn-identifier.purge-interval:P1D}")
  public void scheduled() {
    purge();
  }

  /** 만료된 행을 모두 지운다. 지운 수. */
  public int purge() {
    Instant now = clock.instant();
    int total = 0;
    while (true) {
      Integer n = tx.execute(s -> store.purgeExpired(now, BATCH));
      int deleted = n == null ? 0 : n;
      total += deleted;
      if (deleted < BATCH) {
        break;
      }
    }
    if (total > 0) {
      log.info("탈퇴 소셜 식별값 {}건 파기(보관 만료)", total);
    }
    return total;
  }
}
