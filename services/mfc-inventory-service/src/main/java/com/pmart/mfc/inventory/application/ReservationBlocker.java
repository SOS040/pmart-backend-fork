package com.pmart.mfc.inventory.application;

import com.pmart.mfc.inventory.application.Ledger.ReservedChange;
import com.pmart.mfc.inventory.domain.ReservationState;
import com.pmart.mfc.inventory.infrastructure.ReservationStore;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.LotPart;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.PartRow;
import com.pmart.mfc.inventory.infrastructure.ReservationStore.ReservationRow;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.LotBalance;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 판매 가능(E)이 줄어 LOT 의 R 이 E 를 넘을 때 넘는 양만큼 그 LOT 의 아직 집지 않은 HELD·CONFIRMED 조각을 **나중에 잡은 예약부터** 줄여
 * BLOCKED(이행부족 D) 조각으로 옮긴다 — 품질 격리(IV-09)·실사 손실(IV-11) 공통. 자동 재배분은 하지 않는다(MFC Q-13 "다주문 부족 자동 배분
 * 차단, 영향 약속 보류·수동 해결"). 호출자 트랜잭션 안에서, Ledger 반영 전에 부른다(잠금 순서 예약 행 → 조각 → 잔량).
 */
@Component
public class ReservationBlocker {

  /** 보류 결과: Ledger 에 넣을 R 감소와 ReservationBlocked 로 알릴 조각. */
  public record Result(List<ReservedChange> reserved, List<Blocked> blocked) {}

  public record Blocked(ReservationRow reservation, String sku, String lotId, int qty) {}

  private final StockStore stock;
  private final ReservationStore reservations;
  private final ReservationService reservationService;

  public ReservationBlocker(
      StockStore stock, ReservationStore reservations, ReservationService reservationService) {
    this.stock = stock;
    this.reservations = reservations;
    this.reservationService = reservationService;
  }

  /**
   * @param sellableDrop "sku|lotId" → 이번 변경으로 줄어드는 E(양수)
   * @param reason 조각 사유(QUALITY_HOLD·COUNT_LOSS)
   * @param ref 근거(풀린 조각 사유에 붙임)
   */
  public Result block(
      String mfcId, Map<String, Integer> sellableDrop, String reason, String ref, Instant at) {
    List<ReservedChange> reserved = new ArrayList<>();
    List<Blocked> blocked = new ArrayList<>();
    TreeSet<String> reservationIds = new TreeSet<>();
    for (String k : sellableDrop.keySet()) {
      String[] sl = k.split("\\|", 2);
      reservations
          .activeLotParts(mfcId, sl[0], sl[1])
          .forEach(p -> reservationIds.add(p.reservationId()));
    }
    Map<String, ReservationRow> locked = new LinkedHashMap<>();
    for (String id : reservationIds) {
      reservations.lock(id).ifPresent(r -> locked.put(id, r));
    }
    for (var e : sellableDrop.entrySet()) {
      if (e.getValue() <= 0) {
        continue;
      }
      String[] sl = e.getKey().split("\\|", 2);
      String sku = sl[0];
      String lotId = sl[1];
      LotBalance lb =
          stock.lockLotBalances(mfcId, List.of(sku)).stream()
              .filter(b -> b.lotId().equals(lotId))
              .findFirst()
              .orElse(null);
      if (lb == null) {
        continue; // 잔량 행이 없으면 Ledger 가 부족으로 거절한다
      }
      int deficit = lb.reserved() - (lb.sellable() - e.getValue());
      for (LotPart p : reservations.activeLotParts(mfcId, sku, lotId)) {
        if (deficit <= 0) {
          break;
        }
        ReservationRow r = locked.get(p.reservationId());
        if (r == null) {
          continue; // 잠그지 못한 새 예약 — 이번에는 건드리지 않는다(남은 부족은 Ledger 가 거절)
        }
        int take = Math.min(deficit, p.qty() - p.pickedQty());
        if (take <= 0) {
          continue;
        }
        if (p.qty() - take > 0) {
          reservations.shrinkPart(p.partId(), p.qty() - take, at);
        } else {
          reservations.setPartState(p.partId(), ReservationState.RELEASED, reason + ":" + ref, at);
        }
        reservations.insertPart(
            r.reservationId(),
            new PartRow(
                r.reservationId() + ":Q" + UUID.randomUUID().toString().substring(0, 8),
                p.orderLineId(),
                sku,
                lotId,
                "HAND",
                take,
                ReservationState.BLOCKED,
                reason),
            at);
        reserved.add(new ReservedChange(sku, lotId, -take));
        blocked.add(new Blocked(r, sku, lotId, take));
        deficit -= take;
      }
    }
    return new Result(reserved, blocked);
  }

  /** Ledger 반영 뒤(같은 트랜잭션) ReservationBlocked. */
  public void publish(Result r, String reason, Instant at) {
    for (Blocked b : r.blocked()) {
      reservationService.publishBlocked(b.reservation(), b.sku(), b.lotId(), b.qty(), reason, at);
    }
  }
}
