package com.pmart.claim.application;

import com.pmart.claim.domain.ReturnRules;
import com.pmart.claim.infrastructure.ClaimStore;
import com.pmart.claim.infrastructure.ClaimStore.ClaimRow;
import com.pmart.claim.infrastructure.ClaimStore.PickupRow;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 반품 회수 요청(CL-1) — 시도마다 회수 요청 행 + {@code ReturnPickupRequested}(delivery-dispatch 가 고객 반품 회수 배송을
 * 만든다). 호출자 트랜잭션 안(클레임 잠금 뒤). 회수비 결제됨·면제·자동 재요청·상담원 재요청이 모두 여기로 온다 — 회수비는 여기서 다시 받지 않는다.
 */
@Component
class ReturnPickups {

  private static final Logger log = LoggerFactory.getLogger(ReturnPickups.class);

  private final ClaimStore store;
  private final ClaimEvents events;

  ReturnPickups(ClaimStore store, ClaimEvents events) {
    this.store = store;
    this.events = events;
  }

  /**
   * 다음 시도 번호로 회수 요청.
   *
   * @param requestedBy SYSTEM(결제됨·면제·자동 재요청) 또는 상담원 ID
   */
  PickupRow request(ClaimRow c, String requestedBy, Instant now) {
    List<PickupRow> before = store.pickups(c.claimId());
    int attempt = before.isEmpty() ? 1 : before.get(before.size() - 1).attemptNo() + 1;
    String id = ReturnRules.pickupRequestId(c.claimId(), attempt);
    PickupRow p =
        new PickupRow(id, c.claimId(), attempt, null, "REQUESTED", null, requestedBy, now, now);
    store.insertPickup(p);
    store.updatePickupState(c.claimId(), "REQUESTED", now);
    events.returnPickupRequested(c, store.lines(c.claimId()), id, attempt, now);
    log.info("반품 {} 회수 요청 {}번째 ({})", c.claimId(), attempt, requestedBy);
    return p;
  }
}
