package com.pmart.mfc.capacity.application;

import com.pmart.mfc.capacity.domain.CapacityHold;
import com.pmart.mfc.capacity.infrastructure.HoldStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TTL 이 지난 HELD → EXPIRED(수용 단위 반환). 여러 인스턴스는 FOR UPDATE SKIP LOCKED 로 나눠 잡는다. 점유 합계는 만료 시각이 지난
 * HELD 를 이미 빼고 세므로 이 작업이 늦어도 여력 판정은 맞다 — 이 작업은 상태를 남기는 정리다.
 */
@Service
public class HoldExpirySweeper {

  private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);
  private static final int BATCH = 100;

  private final HoldStore store;
  private final TransactionTemplate tx;
  private final Clock clock;

  public HoldExpirySweeper(HoldStore store, TransactionTemplate tx, Clock clock) {
    this.store = store;
    this.tx = tx;
    this.clock = clock;
  }

  /** 만료 대상을 모두 정리하고 그 수를 돌려준다. */
  @Scheduled(fixedDelayString = "${pmart.capacity.expiry-interval:PT30S}")
  public int expireDue() {
    int total = 0;
    int done;
    do {
      Integer n =
          tx.execute(
              status -> {
                Instant now = clock.instant();
                // 잠금 순서: Hold 행 → 창 행
                List<CapacityHold> due = store.lockExpired(now, BATCH);
                for (CapacityHold h : due) {
                  store.close(h.holdId(), CapacityHold.State.EXPIRED, null, null, now);
                  store.bumpWindow(h.mfcId(), h.window(), now);
                  log.info("capacity hold {} order {} expired", h.holdId(), h.orderId());
                }
                return due.size();
              });
      done = n == null ? 0 : n;
      total += done;
    } while (done == BATCH);
    return total;
  }
}
