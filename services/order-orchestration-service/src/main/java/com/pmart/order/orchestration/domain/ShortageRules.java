package com.pmart.order.orchestration.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 결품 사가 규칙(IV-09 2부, ADR-0045, docs/business/flows/order-checkout-saga.md §2.1). 순수 계산.
 *
 * <ul>
 *   <li>CANCEL_OOS_ONLY(품절 상품만 취소): CHECK_ORDER → RESOLVE_WORK → RELEASE_SHORTFALL →
 *       APPLY_ORDER_CHANGE → REQUEST_REFUND. 남은 주문이 15,000원 미만이 돼도 유지(재결제·배달팁 추가 없음).
 *   <li>CANCEL_ALL(전체 취소): 집품이 시작된 주문의 전체 취소(집품분 판매 제외·복귀, IV-08)가 아직 없어 상신(ESCALATED, 수동).
 *   <li>선택 없음·모름: 출하 보류 → CS(case-service 미구현이라 상신).
 *   <li>결품이 주문의 남은 수량 전부면(봉투값·배달팁 환불 규칙이 전체 취소와 같아야 함) 상신.
 * </ul>
 */
public final class ShortageRules {

  private ShortageRules() {}

  public static final String CANCEL_OOS_ONLY = "CANCEL_OOS_ONLY";
  public static final String CANCEL_ALL = "CANCEL_ALL";

  public static final String CHECK_ORDER = "CHECK_ORDER";
  public static final String RESOLVE_WORK = "RESOLVE_WORK";
  public static final String RELEASE_SHORTFALL = "RELEASE_SHORTFALL";
  public static final String APPLY_ORDER_CHANGE = "APPLY_ORDER_CHANGE";
  public static final String REQUEST_REFUND = "REQUEST_REFUND";

  public static final List<String> STEPS =
      List.of(CHECK_ORDER, RESOLVE_WORK, RELEASE_SHORTFALL, APPLY_ORDER_CHANGE, REQUEST_REFUND);

  /** 사가 시작 판정: null 이면 진행, 아니면 상신 사유 코드. */
  public static @Nullable String escalationAtStart(@Nullable String preference) {
    if (CANCEL_OOS_ONLY.equals(preference)) {
      return null;
    }
    if (CANCEL_ALL.equals(preference)) {
      return "PICKED_CANCEL_NOT_SUPPORTED";
    }
    return "NO_PREFERENCE";
  }

  /** 다음 단계. 마지막이면 null(완료). */
  public static @Nullable String next(String step) {
    int i = STEPS.indexOf(step);
    if (i < 0) {
      throw new IllegalArgumentException("알 수 없는 단계 " + step);
    }
    return i + 1 < STEPS.size() ? STEPS.get(i + 1) : null;
  }

  /** 결품이 주문의 남은(미이행) 수량 전부인가. */
  public static boolean wholeOrder(int remainingUnfulfilled, int shortQty) {
    return remainingUnfulfilled - shortQty <= 0;
  }

  public static String requestId(String sagaId, String step) {
    return sagaId + ":" + step;
  }
}
