package com.pmart.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.claim.domain.ReturnRules.ClaimLineSlot;
import com.pmart.claim.domain.ReturnRules.OrderLine;
import com.pmart.claim.domain.ReturnRules.Requested;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 반품(인계 뒤 회수, CL-1 2026-11-17) 규칙. */
class ReturnRulesTest {

  final List<OrderLine> lines =
      List.of(
          new OrderLine("L1", "E-00000001", 1_900, 10, 10, 0),
          new OrderLine("L2", "E-00000002", 1_000, 2, 0, 0));

  static String code(Runnable r) {
    try {
      r.run();
      return "OK";
    } catch (ClaimRules.Rejection e) {
      return e.code();
    }
  }

  @Test
  void returnableIsHandedOverMinusActiveReturns() {
    assertThat(
            code(
                () ->
                    ReturnRules.checkReturnable(lines, List.of(new Requested("L1", 10)), Map.of())))
        .isEqualTo("OK");
    // 진행·완료 반품 4개가 있으면 6개까지
    assertThat(
            code(
                () ->
                    ReturnRules.checkReturnable(
                        lines, List.of(new Requested("L1", 7)), Map.of("L1", 4))))
        .isEqualTo("QUANTITY_EXCEEDS_AVAILABLE");
    assertThat(
            code(
                () ->
                    ReturnRules.checkReturnable(
                        lines, List.of(new Requested("L1", 6)), Map.of("L1", 4))))
        .isEqualTo("OK");
    // 인계되지 않은 행, 주문에 없는 행, 같은 행 두 번
    assertThat(
            code(
                () ->
                    ReturnRules.checkReturnable(lines, List.of(new Requested("L2", 1)), Map.of())))
        .isEqualTo("NOT_DELIVERED_YET");
    assertThat(
            code(
                () ->
                    ReturnRules.checkReturnable(lines, List.of(new Requested("LX", 1)), Map.of())))
        .isEqualTo("QUANTITY_EXCEEDS_AVAILABLE");
    assertThat(
            code(
                () ->
                    ReturnRules.checkReturnable(
                        lines, List.of(new Requested("L1", 1), new Requested("L1", 1)), Map.of())))
        .isEqualTo("QUANTITY_EXCEEDS_AVAILABLE");
  }

  @Test
  void nothingHandedOverYet() {
    List<OrderLine> notYet = List.of(new OrderLine("L1", "E-00000001", 1_900, 10, 0, 0));
    assertThat(
            code(
                () ->
                    ReturnRules.checkReturnable(notYet, List.of(new Requested("L1", 1)), Map.of())))
        .isEqualTo("NOT_DELIVERED_YET");
  }

  @Test
  void feeIsWaivedOnlyForCompanyFault() {
    assertThat(ReturnRules.fee(null)).isEqualTo(9_000);
    for (String fault : ReturnRules.FAULT_CODES) {
      assertThat(ReturnRules.fee(fault)).isZero();
      assertThat(ReturnRules.feeWaived(fault)).isTrue();
    }
    assertThat(ReturnRules.FAULT_CODES)
        .containsExactlyInAnyOrder(
            "MISDELIVERY", "DEFECTIVE", "DAMAGED_IN_DELIVERY", "SEVERE_DELAY", "WRONG_SUBSTITUTE");
  }

  @Test
  void identityCheckLikeCsIntake() {
    assertThat(ReturnRules.identityRejection(null, null)).isNotNull();
    assertThat(ReturnRules.identityRejection("ORDER_DETAILS_MATCH", null)).isNull();
    assertThat(ReturnRules.identityRejection("APP_SIGNED_IN_CHAT", null)).isNull();
    assertThat(ReturnRules.identityRejection("OTHER", " ")).isNotNull();
    assertThat(ReturnRules.identityRejection("OTHER", "가족 확인 후 본인 통화")).isNull();
    assertThat(ReturnRules.identityRejection("SMS", null)).isNotNull();
  }

  @Test
  void refundIsUnitPriceTimesQtyMinusCumulativeDiscountShare() {
    // 할인 없음 — 단가 × 수량(감액 없음)
    assertThat(ReturnRules.refundAmount(1_900, 10, 0, 0, 3)).isEqualTo(5_700);
    // 할인 1,000원(원수량 3) — 누계 비례 원 미만 버림: 1개 333, 2개째 333, 3개째 334 → 합 정확히 1,000
    long a = ReturnRules.refundAmount(2_000, 3, 1_000, 0, 1);
    long b = ReturnRules.refundAmount(2_000, 3, 1_000, 1, 1);
    long c = ReturnRules.refundAmount(2_000, 3, 1_000, 2, 1);
    assertThat(List.of(a, b, c)).containsExactly(1_667L, 1_667L, 1_666L);
    assertThat(a + b + c).isEqualTo(6_000 - 1_000);
    assertThat(ReturnRules.refundAmount(2_000, 3, 1_000, 0, 3)).isEqualTo(5_000);
    assertThat(ReturnRules.refundAmount(2_000, 3, 1_000, 0, 0)).isZero();
  }

  @Test
  void onlyFirstFailureRetriesAutomatically() {
    assertThat(ReturnRules.autoRetryAfterFailure(1)).isTrue();
    assertThat(ReturnRules.autoRetryAfterFailure(2)).isFalse();
    assertThat(ReturnRules.autoRetryAfterFailure(3)).isFalse(); // 상담원 재요청 뒤 실패도 상담원으로
  }

  /** 추가 결정 3·4: 전체 3번(자동 2 + 상담원 1), 우리 잘못은 횟수에서 뺀다. */
  @Test
  void threeCountedAttemptsAndRiderIncidentNotCounted() {
    assertThat(ReturnRules.failureKind("CUSTOMER_ABSENT", null)).isEqualTo("CUSTOMER");
    assertThat(ReturnRules.failureKind("UNREACHABLE", false)).isEqualTo("CUSTOMER");
    assertThat(ReturnRules.failureKind("ADDRESS_ERROR", null)).isEqualTo("CUSTOMER");
    assertThat(ReturnRules.failureKind("REFUSED", null)).isEqualTo("CUSTOMER");
    assertThat(ReturnRules.failureKind("SOMETHING", true)).isEqualTo("CUSTOMER"); // customerReason
    assertThat(ReturnRules.failureKind("RIDER_INCIDENT", true)).isEqualTo("OURS"); // 사유 코드가 먼저
    assertThat(ReturnRules.failureKind("DAMAGED", null)).isEqualTo("OTHER");
    assertThat(ReturnRules.failureKind("OTHER", false)).isEqualTo("OTHER");
    assertThat(ReturnRules.failureKind(null, null)).isEqualTo("OTHER");

    assertThat(ReturnRules.countsAsAttempt("OURS")).isFalse();
    assertThat(ReturnRules.countsAsAttempt("CUSTOMER")).isTrue();
    assertThat(ReturnRules.countsAsAttempt(null)).isTrue(); // 모르면 센다
    assertThat(ReturnRules.countedFailures(List.of("CUSTOMER", "OURS", "OURS", "OTHER")))
        .isEqualTo(2);

    assertThat(ReturnRules.csRetryAllowed(2)).isTrue(); // 자동 2번 뒤 상담원 1번
    assertThat(ReturnRules.csRetryAllowed(3)).isFalse(); // 다 씀 — 취소만
  }

  /** 추가 결정 2: 취소 때 낸 회수비 — 고객 사유 그대로, 우리 잘못 돌려줌, 파손·기타 상담원(기본 그대로). */
  @Test
  void feeRefundOnCancelFollowsFailureKinds() {
    assertThat(ReturnRules.feeRefundRule(List.of("CUSTOMER", "OURS")))
        .isEqualTo(ReturnRules.FeeRefundRule.KEEP);
    assertThat(ReturnRules.feeRefundRule(List.of("OURS", "OTHER")))
        .isEqualTo(ReturnRules.FeeRefundRule.CS_DECIDES);
    assertThat(ReturnRules.feeRefundRule(List.of("CUSTOMER", "OTHER")))
        .isEqualTo(ReturnRules.FeeRefundRule.KEEP);
    assertThat(ReturnRules.feeRefundRule(List.of("OURS", "OURS")))
        .isEqualTo(ReturnRules.FeeRefundRule.REFUND);
    assertThat(ReturnRules.feeRefundRule(List.of())).isEqualTo(ReturnRules.FeeRefundRule.REFUND);

    assertThat(ReturnRules.refundFee(ReturnRules.FeeRefundRule.KEEP, null)).isFalse();
    assertThat(ReturnRules.refundFee(ReturnRules.FeeRefundRule.KEEP, false)).isFalse();
    assertThat(code(() -> ReturnRules.refundFee(ReturnRules.FeeRefundRule.KEEP, true)))
        .isEqualTo("FEE_REFUND_NOT_ALLOWED");
    assertThat(ReturnRules.refundFee(ReturnRules.FeeRefundRule.REFUND, null)).isTrue();
    assertThat(code(() -> ReturnRules.refundFee(ReturnRules.FeeRefundRule.REFUND, false)))
        .isEqualTo("FEE_REFUND_NOT_ALLOWED");
    assertThat(ReturnRules.refundFee(ReturnRules.FeeRefundRule.CS_DECIDES, null)).isFalse();
    assertThat(ReturnRules.refundFee(ReturnRules.FeeRefundRule.CS_DECIDES, true)).isTrue();
    assertThat(ClaimRules.paymentReason("RETURN_FEE")).isEqualTo("RETURN");
  }

  /** 추가 결정 5: 접수 + 7일 기한(경계 포함). */
  @Test
  void returnDeadline() {
    Instant d = Instant.parse("2026-11-24T00:00:00Z");
    assertThat(ReturnRules.pastDeadline(d, d)).isTrue();
    assertThat(ReturnRules.pastDeadline(d, d.minusSeconds(1))).isFalse();
    assertThat(ReturnRules.pastDeadline(null, d)).isFalse();
  }

  /** 추가 결정 6: 받은 만큼만 끝내기 — 정산 수량 = 받은 수량(반입·검수 중 큰 값). */
  @Test
  void shortCloseSettlesReceivedQuantity() {
    assertThat(
            ReturnRules.shortClose(
                List.of(
                    new ReturnRules.ShortLine("C-L1", 3, 2, 1),
                    new ReturnRules.ShortLine("C-L2", 2, 0, 2),
                    new ReturnRules.ShortLine("C-L3", 1, 0, 0))))
        .containsExactly(Map.entry("C-L1", 2), Map.entry("C-L2", 2), Map.entry("C-L3", 0));
    assertThat(
            code(() -> ReturnRules.shortClose(List.of(new ReturnRules.ShortLine("C-L1", 3, 0, 0)))))
        .isEqualTo("NOTHING_RECEIVED");
    assertThat(
            code(() -> ReturnRules.shortClose(List.of(new ReturnRules.ShortLine("C-L1", 3, 3, 1)))))
        .isEqualTo("NOT_SHORT");
  }

  @Test
  void feePaymentStates() {
    assertThat(ReturnRules.feeInFlight("PENDING")).isTrue();
    assertThat(ReturnRules.feeInFlight("CREATED")).isFalse();
    assertThat(ReturnRules.feeDead("FAILED")).isTrue();
    assertThat(ReturnRules.feeDead("CREATED")).isFalse();
    Instant t = Instant.parse("2026-11-17T00:00:00Z");
    assertThat(ReturnRules.expired(t, t)).isTrue();
    assertThat(ReturnRules.expired(t, t.minusSeconds(1))).isFalse();
    assertThat(ReturnRules.feeChargeRef("CLM-1")).isEqualTo("RTF-CLM-1");
    assertThat(ReturnRules.pickupRequestId("CLM-1", 2)).isEqualTo("CLM-1-P2");
  }

  @Test
  void allocateByOrderLineOrSkuWithinRoom() {
    List<ClaimLineSlot> slots =
        List.of(
            new ClaimLineSlot("C-L1", "L1", "E-00000001", 2),
            new ClaimLineSlot("C-L2", "L2", "E-00000001", 3),
            new ClaimLineSlot("C-L3", "L3", "E-00000003", 1));
    // SKU 로 앞에서부터 남은 칸까지
    assertThat(ReturnRules.allocate(slots, null, "E-00000001", 4))
        .isEqualTo(Map.of("C-L1", 2, "C-L2", 2));
    // orderLineId 가 있으면 그 행만, 넘는 수량은 버림
    assertThat(ReturnRules.allocate(slots, "L3", "E-00000003", 5)).isEqualTo(Map.of("C-L3", 1));
    assertThat(ReturnRules.allocate(slots, null, "E-99999999", 1)).isEmpty();
  }

  @Test
  void shortageRefundGoesToPaymentAsShortage() {
    assertThat(ClaimRules.paymentReason("OUT_OF_STOCK")).isEqualTo("SHORTAGE");
    assertThat(ClaimRules.paymentReason("RETURN")).isEqualTo("RETURN");
    assertThat(ClaimRules.paymentReason("CANCEL_BEFORE_SHIP")).isEqualTo("CANCEL_BEFORE_SHIP");
  }

  /** ADR-0090 A4: 수량 행사 줄은 묶음 단위로만 — 1+1(6개 중 무료 3)은 2개씩, 2+1(6개 중 무료 2)은 3개씩, 무료 없으면 1개씩. */
  @org.junit.jupiter.api.Test
  void quantityOfferLinesReturnOnlyInBundles() {
    assertThat(ReturnRules.bundleSize(6, 3)).isEqualTo(2);
    assertThat(ReturnRules.bundleSize(6, 2)).isEqualTo(3);
    assertThat(ReturnRules.bundleSize(7, 2)).isEqualTo(7);
    assertThat(ReturnRules.bundleSize(5, 0)).isEqualTo(1);
    var lines = List.of(new OrderLine("L1", "E-00000001", 1_900, 6, 6, 5_700, 3));
    ReturnRules.checkReturnable(
        lines, List.of(new ReturnRules.Requested("L1", 2)), java.util.Map.of());
    ReturnRules.checkReturnable(
        lines, List.of(new ReturnRules.Requested("L1", 6)), java.util.Map.of());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                ReturnRules.checkReturnable(
                    lines, List.of(new ReturnRules.Requested("L1", 1)), java.util.Map.of()))
        .isInstanceOf(ClaimRules.Rejection.class)
        .extracting(e -> ((ClaimRules.Rejection) e).code())
        .isEqualTo("RETURN_BUNDLE_REQUIRED");
  }
}
