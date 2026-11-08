package com.pmart.mfc.inventory.domain;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 재고 위치 이동 배정 판정(IV-03, 2026-11-08 사용자 결정). 순수 계산.
 *
 * <ul>
 *   <li>옮길 수 있는 양 = 출발 칸의 집을 수 있는 양(정상·적치 − 보류 − 다른 주문 칸 배정)이고 LOT 가용(A)까지 — 이동 중 물건은 판매 가능에서 빠지므로
 *       예약(R)이 E 를 넘지 않게.
 *   <li>칸당 한 상품: 도착 칸에 다른 상품이 있거나 들어올 예정이면 안 된다.
 *   <li>한 상품은 한 자리(고회전 선반 제외): 도착 칸이 고회전이 아니면, 출발·도착 말고 그 상품이 있는(또는 들어올) 고회전 아닌 칸이 없어야 하고, 출발 칸도
 *       고회전이 아니면 출발 칸에 남는 양이 없어야 한다(나눠 두기 금지).
 *   <li>용량: 도착 칸의 그 상품(지금 + 들어올 예정 + 이번) ≤ 칸 용량.
 * </ul>
 */
public final class MoveRules {

  private MoveRules() {}

  public static final String MOVE_QTY_EXCEEDS_PICKABLE = "MOVE_QTY_EXCEEDS_PICKABLE";
  public static final String MOVE_QTY_EXCEEDS_AVAILABLE = "MOVE_QTY_EXCEEDS_AVAILABLE";
  public static final String LOCATION_HAS_OTHER_SKU = "LOCATION_HAS_OTHER_SKU";
  public static final String SKU_ALREADY_SHELVED = "SKU_ALREADY_SHELVED";
  public static final String SKU_SPLIT_NOT_ALLOWED = "SKU_SPLIT_NOT_ALLOWED";
  public static final String LOCATION_CAPACITY_EXCEEDED = "LOCATION_CAPACITY_EXCEEDED";
  public static final String SAME_LOCATION = "SAME_LOCATION";

  /**
   * @param pickableAtFrom 출발 칸의 그 LOT 집을 수 있는 양
   * @param lotAvailable 그 LOT 의 가용 A(E − R)
   * @param destSkus 도착 칸에 있거나 들어올 예정인 상품들
   * @param skuShelves 이 상품이 있거나 들어올 예정인 칸 → 고회전 여부(출발·도착 포함)
   * @param remainingAtFromAfter 이동 뒤 출발 칸에 남는 이 상품 양(모든 LOT·상태)
   * @param destQtyAfter 이동 뒤 도착 칸의 이 상품 양(지금 + 들어올 예정 + 이번)
   * @param capacity 도착 칸 용량(이 상품 기준)
   * @return 거절 코드, 괜찮으면 null
   */
  public static @Nullable String rejection(
      String sku,
      String from,
      String to,
      boolean fromHighTurnover,
      boolean toHighTurnover,
      int qty,
      int pickableAtFrom,
      int lotAvailable,
      Set<String> destSkus,
      Map<String, Boolean> skuShelves,
      int remainingAtFromAfter,
      int destQtyAfter,
      int capacity) {
    if (from.equals(to)) {
      return SAME_LOCATION;
    }
    if (qty > pickableAtFrom) {
      return MOVE_QTY_EXCEEDS_PICKABLE;
    }
    if (qty > lotAvailable) {
      return MOVE_QTY_EXCEEDS_AVAILABLE;
    }
    for (String s : destSkus) {
      if (!s.equals(sku)) {
        return LOCATION_HAS_OTHER_SKU;
      }
    }
    if (!toHighTurnover) {
      for (var e : skuShelves.entrySet()) {
        if (!e.getKey().equals(from) && !e.getKey().equals(to) && !e.getValue()) {
          return SKU_ALREADY_SHELVED;
        }
      }
      if (!fromHighTurnover && remainingAtFromAfter > 0) {
        return SKU_SPLIT_NOT_ALLOWED;
      }
    }
    if (destQtyAfter > capacity) {
      return LOCATION_CAPACITY_EXCEEDED;
    }
    return null;
  }
}
