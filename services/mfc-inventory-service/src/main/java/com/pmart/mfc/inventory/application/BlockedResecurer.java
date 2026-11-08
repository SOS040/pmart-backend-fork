package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.mfc.inventory.domain.ReservationState;
import com.pmart.mfc.inventory.infrastructure.ReservationStore;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.ReservationRow;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보류분 자동 재확보(2026-11-08 사용자 결정 #5 안 C — "기다리는 동안 되찾기"). 피킹 시작 전 확정 예약의 BLOCKED 조각을, 재고가 돌아오면(격리
 * 해제·입고 적치·다른 예약 해제·실사 증가) 같은 SKU 적격 LOT 의 신규 가용 안에서 다시 잡는다 — {@link ReservationBlocker#resecure} 와
 * 같은 규칙·순서(결제 완료 → 배송 약속일 이른 순 → 먼저 예약한 순). 잡은 만큼 {@code ReservationBlockCleared} → order 가 이행 확인
 * 필요를 닫는다.
 *
 * <p>피킹이 시작된 예약(위치 배정이 불린 {@code picking_started_at})은 건드리지 않는다 — 그때부터는 fulfillment 가 보류분을 결품으로 넘겨
 * 결품 사가가 고객 사전 선택대로 처리한다. 주기 {@code pmart.inventory.blocked-resecure-interval}(기본 1분), 조각마다 별도
 * 트랜잭션.
 */
@Component
public class BlockedResecurer {

  static final int BATCH = 200;
  private static final Logger log = LoggerFactory.getLogger(BlockedResecurer.class);

  public record Outcome(int examined, int resecuredParts, int resecuredQty) {}

  private record Candidate(String partId, String reservationId) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ReservationStore reservations;
  private final ReservationBlocker blocker;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public BlockedResecurer(
      JdbcTemplate jdbc,
      PlatformTransactionManager txm,
      ReservationStore reservations,
      ReservationBlocker blocker,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(txm);
    this.reservations = reservations;
    this.blocker = blocker;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${pmart.inventory.blocked-resecure-interval:PT1M}",
      initialDelayString = "${pmart.inventory.blocked-resecure-interval:PT1M}")
  void scheduled() {
    try {
      run();
    } catch (RuntimeException e) {
      log.warn("보류분 재확보 실패(다음 주기에 다시): {}", e.toString());
    }
  }

  public Outcome run() {
    // 우선순위: 결제 완료(CONFIRMED 예약만 대상) → 약속일 → 먼저 예약
    List<Candidate> due =
        jdbc.query(
            "SELECT p.part_id, p.reservation_id FROM reservation_part p"
                + " JOIN reservation r ON r.reservation_id = p.reservation_id"
                + " WHERE p.state = 'BLOCKED' AND p.qty > 0 AND p.order_line_id IS NOT NULL"
                + " AND r.state = 'CONFIRMED' AND r.picking_started_at IS NULL"
                + " ORDER BY r.promised_date NULLS LAST, r.created_at, p.part_id LIMIT ?",
            (rs, i) -> new Candidate(rs.getString(1), rs.getString(2)),
            BATCH);
    int parts = 0;
    int qty = 0;
    for (Candidate c : due) {
      try {
        Integer got = tx.execute(s -> resecure(c));
        if (got != null && got > 0) {
          parts++;
          qty += got;
        }
      } catch (RuntimeException e) {
        log.warn("보류분 재확보 실패 {}: {}", c.partId(), e.toString());
      }
    }
    if (qty > 0) {
      log.info("보류분 재확보 {}조각 {}개(대상 {})", parts, qty, due.size());
    }
    return new Outcome(due.size(), parts, qty);
  }

  /** 예약 행 잠금 → 조각 다시 확인 → 재확보. @return 잡은 양 */
  private int resecure(Candidate c) {
    ReservationRow r = reservations.lock(c.reservationId()).orElse(null);
    if (r == null || r.state() != ReservationState.CONFIRMED) {
      return 0;
    }
    Boolean started =
        jdbc.queryForObject(
            "SELECT picking_started_at IS NOT NULL FROM reservation WHERE reservation_id = ?",
            Boolean.class,
            c.reservationId());
    if (Boolean.TRUE.equals(started)) {
      return 0;
    }
    var part =
        reservations.pickParts(c.reservationId(), true).stream()
            .filter(p -> p.partId().equals(c.partId()))
            .findFirst()
            .orElse(null);
    if (part == null || part.state() != ReservationState.BLOCKED || part.qty() <= 0) {
      return 0;
    }
    Instant at = clock.instant();
    var before =
        new ReservationBlocker.Result(
            List.of(),
            List.of(
                new ReservationBlocker.Blocked(
                    r,
                    part.partId(),
                    part.orderLineId(),
                    part.sku(),
                    part.lotId(),
                    part.qty(),
                    ReservationState.CONFIRMED)));
    var after =
        blocker.resecure(before, "BLK-RS:" + part.partId() + ":" + part.qty(), "mfc-inventory", at);
    int left = after.blocked().stream().mapToInt(ReservationBlocker.Blocked::qty).sum();
    int got = part.qty() - left;
    if (got <= 0) {
      return 0;
    }
    Integer remaining =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(qty), 0) FROM reservation_part WHERE reservation_id = ? AND sku = ?"
                + " AND state = 'BLOCKED'",
            Integer.class,
            r.reservationId(),
            part.sku());
    publishCleared(
        r,
        part.orderLineId(),
        part.sku(),
        part.lotId(),
        got,
        remaining == null ? 0 : remaining,
        at);
    log.info(
        "보류분 재확보 {} {} {}개(남은 보류 {}) — 주문 {}",
        r.reservationId(),
        part.sku(),
        got,
        remaining,
        r.orderId());
    return got;
  }

  private void publishCleared(
      ReservationRow r,
      String orderLineId,
      String sku,
      String lotId,
      int got,
      int remaining,
      Instant at) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("reservationId", r.reservationId());
    p.put("orderId", r.orderId());
    p.put("mfcId", r.mfcId());
    p.put("orderLineId", orderLineId);
    p.put("sku", sku);
    p.put("lot", lotId);
    p.put("resecuredQty", got);
    p.put("remainingBlockedQty", remaining);
    p.put("clearedAt", at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            "ReservationBlockCleared",
            1,
            "Reservation",
            r.reservationId(),
            r.version(),
            new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
            EventEnvelope.EventSource.REAL,
            p),
        HandoverPostingService.TOPIC);
  }
}
