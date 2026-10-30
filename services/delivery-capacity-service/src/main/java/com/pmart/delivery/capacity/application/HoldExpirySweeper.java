package com.pmart.delivery.capacity.application;

import com.pmart.delivery.capacity.infrastructure.SlotHoldStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TTL 이 지난 HELD 점유를 EXPIRED 로 바꾼다(정원 반환). 여러 인스턴스는 {@code FOR UPDATE SKIP LOCKED} 로 나눠 잡는다.
 *
 * <p>정원 계산·확정 판정은 만료 시각으로 이미 HELD 를 빼므로, 이 작업은 저장 상태를 맞추는 정리다(늦어도 정원이 새지 않는다).
 */
@Component
public class HoldExpirySweeper {

  private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);
  private static final int BATCH = 100;

  private final SlotHoldStore store;
  private final TransactionTemplate tx;
  private final Clock clock;

  public HoldExpirySweeper(SlotHoldStore store, TransactionTemplate tx, Clock clock) {
    this.store = store;
    this.tx = tx;
    this.clock = clock;
  }

  /** 한 번 정리. 바꾼 점유 수. */
  @Scheduled(fixedDelayString = "${pmart.capacity.hold-sweep-interval:PT30S}")
  public int sweep() {
    int total = 0;
    int done;
    do {
      Integer n = tx.execute(status -> store.expireDue(clock.instant(), BATCH));
      done = n == null ? 0 : n;
      total += done;
    } while (done == BATCH);
    if (total > 0) {
      log.info("slot holds expired: {}", total);
    }
    return total;
  }
}
