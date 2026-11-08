package com.pmart.mfc.inventory.application;

import com.pmart.mfc.inventory.application.Ledger.ReservedChange;
import com.pmart.mfc.inventory.domain.ReservationState;
import com.pmart.mfc.inventory.infrastructure.AllocationStore;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 판매 가능(E)이 줄어 LOT 의 R 이 E 를 넘을 때(품질 격리 IV-09·실사 손실 IV-11) 넘는 양만큼 예약을 보류하고, 같은 SKU 의 다른 적격 LOT 으로
 * 다시 잡는다(ADR-0042 결정 1). 사용자 결정(2026-11-08):
 *
 * <ol>
 *   <li><b>칸 기준</b>: 줄어든 칸에 위치 배정된 조각부터(그 칸 배정량 많은 순) → 위치 배정이 없는 조각(나중 예약부터) → 다른 칸에 배정된 조각(나중
 *       예약부터). 아직 집지 않은 양에서만.
 *   <li>{@link #block} — 호출자 Ledger 반영 전: 조각을 줄이고(그 칸 배정을 먼저 깎음) 같은 양의 BLOCKED 조각, R −q.
 *   <li>{@link #resecure} — Ledger 반영 뒤(잔량이 최신): BLOCKED 마다 같은 SKU 적격 LOT 의 신규 가용 안에서 다시 잡고(원래 조각
 *       상태 HELD·CONFIRMED 그대로) 잡은 만큼 BLOCKED 를 줄인다. 다른 주문의 예약은 건드리지 않는다(Q-13 "다주문 부족 자동 배분 차단").
 *   <li>{@link #publish} — 남은 BLOCKED 만 ReservationBlocked. HELD 예약에 BLOCKED 가 남으면 확정이 거절된다(결제 보상).
 * </ol>
 *
 * 잠금 순서 예약 행 → 조각 → 잔량.
 */
@Component
public class ReservationBlocker {

  /** 보류 결과: Ledger 에 넣을 R 감소와 보류한 조각. */
  public record Result(List<ReservedChange> reserved, List<Blocked> blocked) {}

  /**
   * @param partId 만든 BLOCKED 조각
   * @param originalState 줄인 원래 조각의 상태(재확보 조각 상태)
   */
  public record Blocked(
      ReservationRow reservation,
      String partId,
      String orderLineId,
      String sku,
      String lotId,
      int qty,
      ReservationState originalState) {}

  private final StockStore stock;
  private final ReservationStore reservations;
  private final ReservationService reservationService;
  private final AllocationStore allocations;

  public ReservationBlocker(
      StockStore stock,
      ReservationStore reservations,
      ReservationService reservationService,
      AllocationStore allocations) {
    this.stock = stock;
    this.reservations = reservations;
    this.reservationService = reservationService;
    this.allocations = allocations;
  }

  /**
   * @param sellableDrop "sku|lotId" → 이번 변경으로 줄어드는 E(양수)
   * @param dropLocations "sku|lotId" → E 가 줄어든 칸들
   * @param reason 조각 사유(QUALITY_HOLD·COUNT_LOSS)
   * @param ref 근거(풀린 조각 사유에 붙임)
   */
  public Result block(
      String mfcId,
      Map<String, Integer> sellableDrop,
      Map<String, Set<String>> dropLocations,
      String reason,
      String ref,
      Instant at) {
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
      Set<String> here = dropLocations.getOrDefault(e.getKey(), Set.of());
      LotBalance lb =
          stock.lockLotBalances(mfcId, List.of(sku)).stream()
              .filter(b -> b.lotId().equals(lotId))
              .findFirst()
              .orElse(null);
      if (lb == null) {
        continue; // 잔량 행이 없으면 Ledger 가 부족으로 거절한다
      }
      int deficit = lb.reserved() - (lb.sellable() - e.getValue());
      for (LotPart p : ordered(reservations.activeLotParts(mfcId, sku, lotId), here)) {
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
        allocations.shrinkAt(p.partId(), here, take, at); // 없어진 칸의 배정부터 깎는다
        if (p.qty() - take > 0) {
          reservations.shrinkPart(p.partId(), p.qty() - take, at);
        } else {
          reservations.setPartState(p.partId(), ReservationState.RELEASED, reason + ":" + ref, at);
        }
        String blockedId = r.reservationId() + ":Q" + UUID.randomUUID().toString().substring(0, 8);
        reservations.insertPart(
            r.reservationId(),
            new PartRow(
                blockedId,
                p.orderLineId(),
                sku,
                lotId,
                "HAND",
                take,
                ReservationState.BLOCKED,
                reason),
            at);
        reserved.add(new ReservedChange(sku, lotId, -take));
        blocked.add(new Blocked(r, blockedId, p.orderLineId(), sku, lotId, take, p.state()));
        deficit -= take;
      }
    }
    return new Result(reserved, blocked);
  }

  /** 칸 기준 순서: 그 칸 배정 조각(배정량 많은 순) → 배정 없는 조각 → 다른 칸 배정 조각. 각 묶음 안은 들어온 순서(나중 예약부터). */
  private List<LotPart> ordered(List<LotPart> parts, Set<String> here) {
    List<LotPart> atHere = new ArrayList<>();
    Map<String, Integer> hereQty = new LinkedHashMap<>();
    List<LotPart> none = new ArrayList<>();
    List<LotPart> elsewhere = new ArrayList<>();
    for (LotPart p : parts) {
      int mineHere = 0;
      int mineAll = 0;
      for (var a : allocations.active(p.partId())) {
        mineAll += a.remaining();
        if (here.contains(a.locationCode())) {
          mineHere += a.remaining();
        }
      }
      if (mineHere > 0) {
        atHere.add(p);
        hereQty.put(p.partId(), mineHere);
      } else if (mineAll == 0) {
        none.add(p);
      } else {
        elsewhere.add(p);
      }
    }
    // 정렬은 안정 정렬이라 같은 배정량이면 원래 순서(나중 예약부터)
    atHere.sort((a, b) -> Integer.compare(hereQty.get(b.partId()), hereQty.get(a.partId())));
    List<LotPart> out = new ArrayList<>(atHere);
    out.addAll(none);
    out.addAll(elsewhere);
    return out;
  }

  /**
   * Ledger 반영 뒤(같은 트랜잭션): BLOCKED 마다 같은 SKU 적격 LOT 으로 다시 잡고(ADR-0042 결정 1), 잡은 만큼 BLOCKED 를 줄인다.
   *
   * @return 남은 보류(재확보 못 한 양)
   */
  public Result resecure(Result r, String requestId, String sourceSystem, Instant at) {
    List<Blocked> left = new ArrayList<>();
    int n = 0;
    for (Blocked b : priority(r.blocked())) {
      n++;
      var got =
          reservationService.resecure(
              b.reservation(),
              b.orderLineId(),
              b.sku(),
              b.qty(),
              requestId + ":RS" + n,
              sourceSystem,
              at,
              b.originalState());
      int q =
          got.stream().mapToInt(com.pmart.mfc.inventory.domain.LotAllocator.Allocation::qty).sum();
      if (q >= b.qty()) {
        reservations.setPartState(b.partId(), ReservationState.RELEASED, "RESECURED", at);
        continue;
      }
      if (q > 0) {
        reservations.shrinkPart(b.partId(), b.qty() - q, at);
      }
      left.add(
          new Blocked(
              b.reservation(),
              b.partId(),
              b.orderLineId(),
              b.sku(),
              b.lotId(),
              b.qty() - q,
              b.originalState()));
    }
    return new Result(r.reserved(), left);
  }

  /**
   * 재확보 순서(사용자 결정 2026-11-08): ① 결제를 마친 주문(CONFIRMED) 먼저 — 결제 중(HELD) 주문은 못 채워도 확정 거절 → 결제 취소로 끝나
   * 고객 피해가 작다 ② 배송 약속일 이른 순 ③ 먼저 예약한 순.
   */
  static List<Blocked> priority(List<Blocked> in) {
    List<Blocked> out = new ArrayList<>(in);
    out.sort(
        java.util.Comparator.comparing(
                (Blocked b) -> b.originalState() == ReservationState.CONFIRMED ? 0 : 1)
            .thenComparing(
                b -> b.reservation().promisedDate(),
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
            .thenComparing(b -> b.reservation().createdAt()));
    return out;
  }

  /** 남은 보류만 ReservationBlocked. */
  public void publish(Result r, String reason, Instant at) {
    for (Blocked b : r.blocked()) {
      reservationService.publishBlocked(b.reservation(), b.sku(), b.lotId(), b.qty(), reason, at);
    }
  }
}
