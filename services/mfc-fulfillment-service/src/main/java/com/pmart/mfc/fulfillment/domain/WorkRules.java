package com.pmart.mfc.fulfillment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 작업 접수 규칙(슬라이스 6a, docs/business/flows/order-checkout-saga.md §2.0). 순수 계산만 — 저장·호출은 application.
 */
public final class WorkRules {

  /** 같은 MFC 재시도 대상 거절 사유(D6-5). 여력 부족·LOT 부적격·이미 중지는 재시도하지 않는다. */
  public static final Set<String> RETRYABLE = Set.of("INVENTORY_MISMATCH", "OPERATION_RESTRICTED");

  public static final String CONFIG_REQUIRED = "CONFIG_REQUIRED";

  private WorkRules() {}

  /** 요청 행(orderLineId·sku·qty). */
  public record Line(String orderLineId, String sku, int qty) {}

  /** 재고 예약 조각(mfc-inventory Reservation.parts). */
  public record ReservationPart(String sku, int qty, String state) {}

  /** PICK 시작 상태. readyAt null + holdReason CONFIG_REQUIRED = 센터 설정 없음. */
  public record PickStart(String state, @Nullable Instant readyAt, @Nullable String holdReason) {}

  public static boolean retryable(String rejectReason) {
    return RETRYABLE.contains(rejectReason);
  }

  /**
   * 이미 중지된 수량(order_work_stop)을 뺀 작업 대상. 0 이 된 행은 뺀다.
   *
   * @param stopped orderLineId → 중지 수량 합
   */
  public static List<Line> remaining(List<Line> requested, Map<String, Integer> stopped) {
    List<Line> out = new ArrayList<>();
    for (Line l : requested) {
      int left = l.qty() - stopped.getOrDefault(l.orderLineId(), 0);
      if (left > 0) {
        out.add(new Line(l.orderLineId(), l.sku(), left));
      }
    }
    return out;
  }

  /**
   * 재고 예약이 작업 대상을 덮는가: 예약 전체가 CONFIRMED 이고, SKU 마다 CONFIRMED 조각 합 ≥ 필요 수량. 예약이 없으면(null) 덮지 못한다.
   * (D6-5 "원래 예약만" — 새 예약은 보지 않는다.)
   */
  public static boolean reservationCovers(
      @Nullable String reservationState, List<ReservationPart> parts, List<Line> needed) {
    if (!"CONFIRMED".equals(reservationState)) {
      return false;
    }
    Map<String, Integer> have = new LinkedHashMap<>();
    for (ReservationPart p : parts) {
      if ("CONFIRMED".equals(p.state())) {
        have.merge(p.sku(), p.qty(), Integer::sum);
      }
    }
    Map<String, Integer> need = new LinkedHashMap<>();
    for (Line l : needed) {
      need.merge(l.sku(), l.qty(), Integer::sum);
    }
    for (Map.Entry<String, Integer> e : need.entrySet()) {
      if (have.getOrDefault(e.getKey(), 0) < e.getValue()) {
        return false;
      }
    }
    return true;
  }

  /**
   * PICK 준비(D6-3). 슬라이스 시작이 있는 주문(예약 주문)은 시작 − N분, N 미설정이면 WAITING·CONFIG_REQUIRED(0·즉시로 대체하지 않음).
   * 슬롯이 없는 주문(즉시 배송)은 바로 READY. 준비 시각이 이미 지났으면 READY.
   */
  public static PickStart pickStart(
      @Nullable Instant slotStart, @Nullable Duration leadTime, Instant now) {
    if (slotStart == null) {
      return new PickStart("READY", now, null);
    }
    if (leadTime == null) {
      return new PickStart("WAITING", null, CONFIG_REQUIRED);
    }
    Instant readyAt = slotStart.minus(leadTime);
    return readyAt.isAfter(now)
        ? new PickStart("WAITING", readyAt, null)
        : new PickStart("READY", readyAt, null);
  }

  /**
   * 중지 수량을 작업 행에 나눈다(접수 뒤 중지 — 6a 는 피킹 시작이 없어 전부 작업 전 취소). 행마다 남은 잔량까지만.
   *
   * @return orderLineId → 이번에 취소할 수량
   */
  public static Map<String, Integer> cancelAgainst(
      Map<String, Integer> remainingByLine, List<Line> stop) {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (Line s : stop) {
      int left =
          remainingByLine.getOrDefault(s.orderLineId(), 0) - out.getOrDefault(s.orderLineId(), 0);
      int c = Math.min(left, s.qty());
      if (c > 0) {
        out.merge(s.orderLineId(), c, Integer::sum);
      }
    }
    return out;
  }
}
