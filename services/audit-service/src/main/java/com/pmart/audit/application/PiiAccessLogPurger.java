package com.pmart.audit.application;

import com.pmart.audit.infrastructure.PiiAccessLogStore;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 개인정보 열람 기록 보존 기간 지난 행 파기(2026-11-15, ADR-0062 결정 9 — 2년). 열람 시각({@code occurred_at}) 기준. 한 번에
 * {@code batch} 행씩, 트랜잭션마다 {@code pmart.audit_purge} 를 켜서 V3 트리거가 이 DELETE 만 허용하게 한다. 지운 수만 로그(내용
 * 없음).
 */
@Component
public class PiiAccessLogPurger {

  private static final Logger log = LoggerFactory.getLogger(PiiAccessLogPurger.class);
  private static final int BATCH = 500;

  private final PiiAccessLogStore store;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Period retention;

  public PiiAccessLogPurger(
      PiiAccessLogStore store,
      TransactionTemplate tx,
      Clock clock,
      @Value("${pmart.audit.pii-retention:P2Y}") String retention) {
    this.store = store;
    this.tx = tx;
    this.clock = clock;
    this.retention = Period.parse(retention);
  }

  @Scheduled(
      initialDelayString = "${pmart.audit.pii-purge-interval:PT6H}",
      fixedDelayString = "${pmart.audit.pii-purge-interval:PT6H}")
  public void scheduled() {
    purge();
  }

  /** 보존 기간 지난 행을 모두 지운다. 지운 수. */
  public int purge() {
    Instant before = clock.instant().atOffset(ZoneOffset.UTC).minus(retention).toInstant();
    int total = 0;
    while (true) {
      Integer n = tx.execute(s -> store.purgeOlderThan(before, BATCH));
      int deleted = n == null ? 0 : n;
      total += deleted;
      if (deleted < BATCH) {
        break;
      }
    }
    if (total > 0) {
      log.info("개인정보 열람 기록 {}건 파기(보존 {} 지남)", total, retention);
    }
    return total;
  }
}
