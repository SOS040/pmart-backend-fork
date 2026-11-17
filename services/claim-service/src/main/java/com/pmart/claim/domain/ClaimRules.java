package com.pmart.claim.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 취소 클레임 규칙(OMS 8.3·8.4·10, A.1.11). 2026-10-31 구현 범위: 고객의 주문 전체 취소(피킹 전).
 *
 * <ul>
 *   <li>고객 임의 부분 취소는 열지 않는다(OMS 8.4 "고객 임의 부분 취소 정책을 확대하지 않는다") — 모든 주문행 × 미이행 수량이어야 한다.
 *   <li>취소 가능 수량 = 원수량 − 기취소 − 인계 − 진행 중 클레임(= 미이행, 진행 중 클레임은 주문당 하나로 막는다).
 *   <li>환불액(전체 취소) = 원 승인액 전액 — 상품액 + 봉투값(전체 취소만 환불) + 확정 배달팁(사용자 결정 2026-10-31). 1차는 할인 없음.
 * </ul>
 */
public final class ClaimRules {

  private ClaimRules() {}

  /** 주문 원장의 행(심사 근거). */
  public record OrderLine(
      String orderLineId, String sku, long unitPrice, int originalQty, int unfulfilledQty) {}

  /** 요청 행. */
  public record Requested(String orderLineId, int qty) {}

  /** 심사 거절 — code 는 계약의 422 코드. */
  public static final class Rejection extends RuntimeException {
    private final String code;

    public Rejection(String code, String message) {
      super(message);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  /**
   * 고객 전체 취소 요청 심사(CONFIRMED 만). 통과하면 아무것도 돌려주지 않고, 아니면 {@link Rejection}.
   *
   * @param orderStatus order 원장 상태
   */
  public static void checkFullCancel(
      String orderStatus, List<OrderLine> lines, List<Requested> requested) {
    checkFullCancel(orderStatus, lines, requested, false);
  }

  /**
   * 전체 취소 요청 심사.
   *
   * @param system 시스템 자동 취소면 준비 중(PREPARING — 피킹 시작 뒤)도 받는다. 현장 중지가 집품분을 취소 복귀로 돌린다(IV-08,
   *     ADR-0046). 고객 접수는 CONFIRMED 만
   */
  public static void checkFullCancel(
      String orderStatus, List<OrderLine> lines, List<Requested> requested, boolean system) {
    boolean ok = "CONFIRMED".equals(orderStatus) || (system && "PREPARING".equals(orderStatus));
    if (!ok) {
      throw new Rejection("ORDER_NOT_CANCELLABLE", "지금 상태(" + orderStatus + ")의 주문은 취소할 수 없습니다.");
    }
    Map<String, OrderLine> byId = new HashMap<>();
    for (OrderLine l : lines) {
      byId.put(l.orderLineId(), l);
    }
    Map<String, Integer> req = new HashMap<>();
    for (Requested r : requested) {
      OrderLine l = byId.get(r.orderLineId());
      if (l == null) {
        throw new Rejection("QUANTITY_EXCEEDS_AVAILABLE", "주문에 없는 상품입니다: " + r.orderLineId());
      }
      if (req.put(r.orderLineId(), r.qty()) != null) {
        throw new Rejection("QUANTITY_EXCEEDS_AVAILABLE", "같은 상품이 두 번 들어 있습니다: " + r.orderLineId());
      }
      if (r.qty() > l.unfulfilledQty()) {
        throw new Rejection(
            "QUANTITY_EXCEEDS_AVAILABLE",
            "취소 가능한 수량(" + l.unfulfilledQty() + ")보다 많습니다: " + r.orderLineId());
      }
    }
    for (OrderLine l : lines) {
      if (l.unfulfilledQty() == 0) {
        continue;
      }
      Integer q = req.get(l.orderLineId());
      if (q == null || q != l.unfulfilledQty()) {
        throw new Rejection("PARTIAL_CANCEL_NOT_ALLOWED", "주문 전체만 취소할 수 있습니다.");
      }
    }
    if (req.isEmpty()) {
      throw new Rejection("ORDER_NOT_CANCELLABLE", "취소할 수량이 없습니다.");
    }
  }

  /**
   * 환불 의도 사유 → payment 취소 사유(계약 CancellationRequest.reason). 결품 클레임 사유 OUT_OF_STOCK 은 payment 에서
   * SHORTAGE 다(2026-11-17 발견 — 그대로 보내면 payment 가 400 으로 거절해 결품 부분 환불이 FAILED 가 됐다). 나머지는 같은 이름.
   */
  public static String paymentReason(String refundReason) {
    return "OUT_OF_STOCK".equals(refundReason) ? "SHORTAGE" : refundReason;
  }

  /** 환불 금액 구성(전체 취소). */
  public record RefundAmount(long amount, long itemAmount, long bagFee, long deliveryTip) {}

  /** 전체 취소 환불액 = 원 승인액 전액. 구성 합과 승인액이 다르면(대사 대상) null — 자동 환불하지 않는다. */
  public static @Nullable RefundAmount fullRefund(
      long approvedAmount, long itemAmount, long bagFee, long deliveryTip) {
    if (itemAmount + bagFee + deliveryTip != approvedAmount) {
      return null;
    }
    return new RefundAmount(approvedAmount, itemAmount, bagFee, deliveryTip);
  }
}
