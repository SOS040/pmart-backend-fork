package com.pmart.order.domain;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 고객 탈퇴 의무 확인 규칙(2026-11-20, ADR-0077 결정 3, 계약 0.16.0). 탈퇴 완료를 막는 order 쪽 미결 의무를 가른다 — 끝나지 않은 주문
 * {@link #ORDER_IN_PROGRESS}, 결제 결과를 모르는 것 {@link #PAYMENT_UNKNOWN}(ref = orderId). 환불·반품·분쟁은
 * claim-service 가 본다. 모르면 미결로 둔다(거래 없음으로 추정하지 않음 — OMS §13).
 */
public final class WithdrawalRules {

  public static final String ORDER_IN_PROGRESS = "ORDER_IN_PROGRESS";
  public static final String PAYMENT_UNKNOWN = "PAYMENT_UNKNOWN";

  /**
   * 더 바뀌지 않는 주문 상태(끝 상태). COMPLETED = 전부 전달(배송 완료 — 그 뒤 반품·환불은 claim 의무), CANCELLED = 전부 출고 전 취소,
   * EXPIRED. 주문 내역 숨김 가능 상태({@link OrderHistoryRules#FINISHED})와 같다.
   */
  public static final Set<String> ORDER_FINISHED = OrderHistoryRules.FINISHED;

  /** 결제 진입 사가가 이 상태면 이번 시도분은 해제·종료됐다(결제 의무 없음). */
  public static final Set<String> CHECKOUT_ENDED = Set.of("COMPENSATED");

  private WithdrawalRules() {}

  /** 확정 주문(원장) 상태 → 미결 의무 종류. 끝 상태면 null. 원장의 PAYMENT_UNKNOWN 은 결제 불명, 그 밖(모르는 값 포함)은 진행 중. */
  public static @Nullable String orderObligation(String status) {
    if (ORDER_FINISHED.contains(status)) {
      return null;
    }
    return "PAYMENT_UNKNOWN".equals(status) ? PAYMENT_UNKNOWN : ORDER_IN_PROGRESS;
  }

  /**
   * 확정 주문이 없는 결제 진입(초안에 주문 ID·사가가 남은 것)의 사가 상태 → 미결 의무 종류. 결제 진입 전 초안은 여기 오지 않는다(의무 아님).
   *
   * <ul>
   *   <li>COMPENSATED(보상 종료) → null
   *   <li>COMPLETED(사가는 끝났는데 원장 확정이 아직 안 보임) → 진행 중 주문
   *   <li>RUNNING·WAITING_RESULT·UNKNOWN_TRACKING·COMPENSATING·RECONCILIATION_REQUIRED·ESCALATED·모르는
   *       상태·조회 실패(null) → 결제 불명(결과가 아직 정해지지 않음 — 승인될 수 있다)
   * </ul>
   */
  public static @Nullable String checkoutObligation(@Nullable String sagaState) {
    if (sagaState != null && CHECKOUT_ENDED.contains(sagaState)) {
      return null;
    }
    return "COMPLETED".equals(sagaState) ? ORDER_IN_PROGRESS : PAYMENT_UNKNOWN;
  }
}
