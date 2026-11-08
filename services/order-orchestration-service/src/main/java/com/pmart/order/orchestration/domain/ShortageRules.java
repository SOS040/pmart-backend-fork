package com.pmart.order.orchestration.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 결품 사가 규칙(IV-09 2부, ADR-0045 → ADR-0049, docs/business/flows/order-checkout-saga.md §2.1). 순수 계산.
 *
 * <ul>
 *   <li><b>결품은 고객 사전 선택과 관계없이 무조건 부분 취소·환불</b>(사용자 결정 2026-11-08, ADR-0049): CHECK_ORDER →
 *       RESOLVE_WORK → RELEASE_SHORTFALL → APPLY_ORDER_CHANGE → REQUEST_REFUND. 남은 주문이 15,000원 미만이
 *       돼도 유지(재결제·배달팁 추가 없음). 사전 선택 값(outOfStockPreference)은 기록만 한다 — 전체 취소 선택·선택 없음도 같은 처리, 상신 없음.
 *   <li>예외: 결품이 주문의 남은 수량 전부면(보낼 상품이 없다) CHECK_ORDER → REQUEST_FULL_CANCEL — claim 시스템 전체 취소(reason
 *       OUT_OF_STOCK, 봉투값·배달팁까지 환불, ADR-0046).
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

  /** 전체 취소로 넘김(ADR-0046) — 마지막 단계. */
  public static final String REQUEST_FULL_CANCEL = "REQUEST_FULL_CANCEL";

  public static final List<String> STEPS =
      List.of(CHECK_ORDER, RESOLVE_WORK, RELEASE_SHORTFALL, APPLY_ORDER_CHANGE, REQUEST_REFUND);

  /** 사가 시작 판정: null 이면 진행. 결품은 무조건 부분 취소·환불이라(ADR-0049) 사전 선택으로 상신하지 않는다. */
  public static @Nullable String escalationAtStart(@Nullable String preference) {
    return null;
  }

  /** 첫 단계 — 언제나 주문 확인(남은 수량 전부 결품이면 거기서 전체 취소로). */
  public static String firstStep(@Nullable String preference) {
    return CHECK_ORDER;
  }

  /** 다음 단계. 마지막이면 null(완료). */
  public static @Nullable String next(String step) {
    if (REQUEST_FULL_CANCEL.equals(step)) {
      return null;
    }
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
