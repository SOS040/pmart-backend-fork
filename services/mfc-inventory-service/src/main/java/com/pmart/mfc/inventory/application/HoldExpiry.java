package com.pmart.mfc.inventory.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 예약 TTL 만료 주기 작업. 한 번에 {@link #BATCH} 건씩 별도 트랜잭션으로 처리하고, 가득 찼으면 이어서 돈다. 여러 인스턴스는 SKIP LOCKED 로 나눠
 * 잡는다.
 */
@Component
class HoldExpiry {

  static final int BATCH = 100;
  private static final Logger log = LoggerFactory.getLogger(HoldExpiry.class);

  private final ReservationService reservations;

  HoldExpiry(ReservationService reservations) {
    this.reservations = reservations;
  }

  @Scheduled(
      fixedDelayString = "${pmart.inventory.hold-expiry-interval:PT5S}",
      initialDelayString = "${pmart.inventory.hold-expiry-interval:PT5S}")
  void sweep() {
    try {
      int n;
      do {
        n = reservations.expireDue(BATCH);
      } while (n == BATCH);
    } catch (RuntimeException e) {
      // 다음 주기에 다시 본다(DB 일시 장애 등). 반영된 배치는 이미 커밋됐다.
      log.warn("hold expiry sweep failed: {}", e.toString());
    }
  }
}
