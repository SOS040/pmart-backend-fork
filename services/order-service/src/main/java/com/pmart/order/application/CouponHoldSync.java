package com.pmart.order.application;

import com.pmart.order.infrastructure.CouponHolds;
import com.pmart.order.infrastructure.OrchestrationClient;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 쿠폰 점유 맞추기(ADR-0082). 결제 진입 때 잡은 점유를 주문 결과에 맞춘다 — 확정 주문이 생기면 사용 확정, 결제가 주문 없이 끝나면(사가가 닫힘, 또는 사가 없이
 * 견적 만료) 해제, 확정 주문이 전체 취소(CANCELLED)되면 복원. promotion 쪽 동작은 멱등이라 실패하면 다음 회차에 다시 한다.
 */
@Component
public class CouponHoldSync {

  private static final Logger log = LoggerFactory.getLogger(CouponHoldSync.class);

  /** 사가가 아직 열려 있거나(결과 대기) 확정으로 끝난 상태 — 해제하지 않는다. */
  static final List<String> KEEP =
      List.of(
          "RUNNING",
          "WAITING_RESULT",
          "UNKNOWN_TRACKING",
          "COMPENSATING",
          "RECONCILIATION_REQUIRED",
          "COMPLETED");

  private final CouponHolds holds;
  private final OrchestrationClient orchestration;
  private final Clock clock;

  public CouponHoldSync(CouponHolds holds, OrchestrationClient orchestration, Clock clock) {
    this.holds = holds;
    this.orchestration = orchestration;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${pmart.order.coupon-sync-interval:PT30S}",
      initialDelayString = "${pmart.order.coupon-sync-interval:PT30S}")
  public void run() {
    for (CouponHolds.Pending p : holds.open(200)) {
      try {
        step(p);
      } catch (RuntimeException e) {
        log.warn(
            "쿠폰 점유 맞추기 실패 draft={} hold={} — 다음 회차에 다시", p.hold().draftId(), p.hold().holdId(), e);
      }
    }
  }

  void step(CouponHolds.Pending p) {
    Instant now = clock.instant();
    CouponHolds.HoldRow h = p.hold();
    if ("HELD".equals(h.state())) {
      if (p.orderStatus() != null) {
        apply(h, "CONFIRM", now);
        return;
      }
      boolean ended;
      if (p.sagaId() != null) {
        JsonNode saga = orchestration.saga(p.sagaId());
        if (saga == null) {
          return; // 확인 불가 — 다음에
        }
        ended = !KEEP.contains(saga.path("state").asString());
      } else {
        ended = now.isAfter(p.quoteExpiresAt());
      }
      if (ended) {
        apply(h, "RELEASE", now);
      }
      return;
    }
    if ("CONFIRMED".equals(h.state()) && "CANCELLED".equals(p.orderStatus())) {
      apply(h, "RESTORE", now);
    }
  }

  private void apply(CouponHolds.HoldRow h, String action, Instant now) {
    String state = holds.transition(h.holdId(), action);
    if (!state.isBlank() && !state.equals(h.state())) {
      holds.state(h.draftId(), state, now);
      log.info("쿠폰 점유 {} draft={} → {}", action, h.draftId(), state);
    }
  }
}
