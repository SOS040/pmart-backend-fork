package com.pmart.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NoticesTest {

  @Test
  void returnPickupNoticesPointToOrderWithoutPromisingRefundTime() {
    assertThat(Notices.returnPickedUp("ORD-1").destinationRef()).isEqualTo("ORD-1");
    assertThat(Notices.returnPickedUp("ORD-1").body()).contains("확인한 뒤 환불");
    assertThat(Notices.returnPickupMissed("ORD-1").title()).isEqualTo("반품 상품을 회수하지 못했어요");
    assertThat(Notices.returnPickupDispatching("ORD-1").destinationType()).isEqualTo("ORDER");
  }

  @Test
  void appealDecidedSaysResultOnly() {
    var lifted = Notices.appealDecided(true, false);
    org.assertj.core.api.Assertions.assertThat(lifted.templateKey())
        .isEqualTo("RIDER_APPEAL_DECIDED");
    org.assertj.core.api.Assertions.assertThat(lifted.body()).contains("제한이 풀렸어요");
    org.assertj.core.api.Assertions.assertThat(Notices.appealDecided(false, true).body())
        .contains("유지");
  }

  @Test
  void payoutResultHasDateButNoAmountOrReason() {
    var paid = Notices.payoutResult(true, "2026-11-21");
    org.assertj.core.api.Assertions.assertThat(paid.templateKey()).isEqualTo("RIDER_PAYOUT_PAID");
    org.assertj.core.api.Assertions.assertThat(paid.body()).contains("11월 21일").doesNotContain("원");
    var failed = Notices.payoutResult(false, "2026-11-21");
    org.assertj.core.api.Assertions.assertThat(failed.templateKey())
        .isEqualTo("RIDER_PAYOUT_FAILED");
  }

  @org.junit.jupiter.api.Test
  void riderDeductionNoticesCarryAmountAndDeadline() {
    var s =
        Notices.riderDeduction(
            "RiderDeductionScheduled",
            "D28767701-3",
            15970L,
            java.time.Instant.parse("2026-11-29T06:00:00Z"));
    org.assertj.core.api.Assertions.assertThat(s).isNotNull();
    org.assertj.core.api.Assertions.assertThat(s.templateKey())
        .isEqualTo("RIDER_DEDUCTION_SCHEDULED");
    org.assertj.core.api.Assertions.assertThat(s.body())
        .contains("D28767701-3")
        .contains("15,970원")
        .contains("11월 29일 15:00까지");
    var c = Notices.riderDeduction("RiderDeductionConfirmed", null, 15970L, null);
    org.assertj.core.api.Assertions.assertThat(c.body()).contains("15,970원").contains("다음 정산");
    var x = Notices.riderDeduction("RiderDeductionCancelled", "D1", 100L, null);
    org.assertj.core.api.Assertions.assertThat(x.templateKey())
        .isEqualTo("RIDER_DEDUCTION_CANCELLED");
    org.assertj.core.api.Assertions.assertThat(
            Notices.riderDeduction("RiderDeductionOther", null, null, null))
        .isNull();
  }

  @org.junit.jupiter.api.Test
  void manualRiderDeductionSaysSupportCenterNotRecovery() {
    var m =
        Notices.riderDeduction(
            "RiderDeductionScheduled",
            "D1-2",
            5000L,
            java.time.Instant.parse("2026-11-29T06:00:00Z"),
            "MANUAL");
    org.assertj.core.api.Assertions.assertThat(m.body())
        .contains("지원센터가")
        .contains("5,000원")
        .doesNotContain("회수가 끝나지 않아");
    var auto =
        Notices.riderDeduction(
            "RiderDeductionScheduled", "D1-2", 5000L, null, "MISDELIVERY_RECOVERY");
    org.assertj.core.api.Assertions.assertThat(auto.body()).contains("회수가 끝나지 않아");
  }

  @Test
  void earningAdjustedShowsAmountAndDateOnlyWhenPaid() {
    var paid = Notices.earningAdjusted("DLV-1", true, 3900L, "2026-11-20");
    assertThat(paid.body()).contains("3,900원").contains("11월 20일");
    var none = Notices.earningAdjusted("DLV-1", false, null, null);
    assertThat(none.body()).contains("지급되지 않아요").doesNotContain("원이");
  }

  @Test
  void riderRestrictionLiftedOnlyWhenSomethingWasRestricted() {
    assertThat(Notices.riderRestriction("NORMAL", "DISPATCH_RESTRICTED", null).title())
        .isEqualTo("계정 제한이 풀렸어요");
    assertThat(Notices.riderRestriction("NORMAL", null, null)).isNotNull(); // previous 없는 옛 사건은 그대로
    assertThat(Notices.riderRestriction("NORMAL", "NORMAL", null)).isNull();
    assertThat(Notices.riderRestriction("DISPATCH_RESTRICTED", "NORMAL", null).body())
        .doesNotContain("사유");
  }

  @Test
  void orderConfirmedShowsAmountAndArrivalWindow() {
    var n = Notices.orderConfirmed("ORD-1", 32100L, "2026-11-17", "13:00", "14:00");
    assertThat(n.body()).contains("32,100원").contains("11월 17일 13:00~14:00");
    assertThat(n.destinationType()).isEqualTo("ORDER");
    assertThat(n.destinationRef()).isEqualTo("ORD-1");
    // 도착 창이 없으면 그 문장을 빼고, 지어내지 않는다
    assertThat(Notices.orderConfirmed("ORD-1", null, null, null, null).body())
        .doesNotContain("도착 예정")
        .doesNotContain("원");
  }

  @Test
  void cancellationDoesNotClaimRefundCompleted() {
    var n = Notices.orderCancelled("ORD-1", "OUT_OF_STOCK");
    assertThat(n.title()).isEqualTo("품절로 주문이 취소되었어요");
    assertThat(n.body()).doesNotContain("환불되었").contains("환불 결과가 나오면");
    assertThat(Notices.orderCancelled("ORD-1", "CUSTOMER_REQUEST").title()).isEqualTo("주문이 취소되었어요");
  }

  @Test
  void refundOnlyForKnownResults() {
    var ok = Notices.refund("SUCCESS", 5000L, "ORD-1", "CLM-1");
    assertThat(ok).isNotNull();
    assertThat(ok.body()).startsWith("5,000원을");
    assertThat(ok.destinationType()).isEqualTo("REFUND");
    assertThat(ok.destinationRef()).isEqualTo("CLM-1");
    assertThat(Notices.refund("FAILED", 5000L, "ORD-1", null).destinationType()).isEqualTo("ORDER");
    assertThat(Notices.refund("UNKNOWN", 5000L, "ORD-1", "CLM-1")).isNull();
  }

  @Test
  void shortageUsesProductNameOrGenericLabel() {
    assertThat(Notices.shortage("ORD-1", "사과 1kg").body()).startsWith("사과 1kg의");
    assertThat(Notices.shortage("ORD-1", null).body()).startsWith("주문한 상품의");
  }

  /** 레퍼런스 10-3(2026-11-19): 준비 중 문구, 배송 진행 알림의 목적지. */
  @org.junit.jupiter.api.Test
  void referenceDeliveryNotices() {
    var scheduled = Notices.preparationStarted("O-1", "사과 1kg", 5, "2026-11-20", "08:00", "09:00");
    org.assertj.core.api.Assertions.assertThat(scheduled.title()).isEqualTo("예약하신 주문을 준비 중이에요");
    org.assertj.core.api.Assertions.assertThat(scheduled.body())
        .startsWith("주문하신 상품(사과 1kg 외 4개)을 준비하고 있어요.")
        .contains("08:00~09:00 사이에 도착할 예정이에요");
    var now = Notices.preparationStarted("O-1", null, 0, null, null, null);
    org.assertj.core.api.Assertions.assertThat(now.title()).isEqualTo("주문하신 상품을 준비 중이에요");
    org.assertj.core.api.Assertions.assertThat(now.body()).startsWith("주문하신 상품을 준비하고 있어요.");
    org.assertj.core.api.Assertions.assertThat(Notices.arrivingSoon("O-1", "D-1").destinationType())
        .isEqualTo("DELIVERY");
    org.assertj.core.api.Assertions.assertThat(
            Notices.headingToCustomer("O-1", null).destinationRef())
        .isEqualTo("O-1");
    org.assertj.core.api.Assertions.assertThat(Notices.pickedUp("O-1", "D-1").destinationRef())
        .isEqualTo("D-1");
  }

  /** CL-5(2026-11-19): 사유별 취소 문구 — 배차 불가·품절·그 밖. */
  @org.junit.jupiter.api.Test
  void cancelNoticeByReason() {
    org.assertj.core.api.Assertions.assertThat(
            Notices.orderCancelled("O-1", "DISPATCH_UNAVAILABLE").body())
        .startsWith("배달 기사를 찾지 못해 주문을 취소했어요. 늦어져서 죄송해요. 결제 금액은 전액 환불되고, 보상으로 2,000원 쿠폰을 드려요");
    org.assertj.core.api.Assertions.assertThat(
            Notices.orderCancelled("O-1", "OUT_OF_STOCK").title())
        .isEqualTo("품절로 주문이 취소되었어요");
    org.assertj.core.api.Assertions.assertThat(
            Notices.orderCancelled("O-1", "CHANGE_OF_MIND").title())
        .isEqualTo("주문이 취소되었어요");
  }

  /** 쿠폰 도착(2026-11-21 ADR-0082 5단계): 배차 불가 보상은 그 주문으로, 가입 축하는 목적지 없음, 기한은 서울 날짜. */
  @Test
  void couponIssuedNotices() {
    Notices.Notice comp =
        Notices.couponIssued(
            "ADMIN",
            "DISPATCH_UNAVAILABLE",
            "O-9",
            "배차 불가 보상 2,000원",
            "AMOUNT",
            2000,
            "2026-12-20T14:59:59.999Z");
    assertThat(comp.title()).isEqualTo("보상 쿠폰이 도착했어요");
    assertThat(comp.body()).contains("2,000원 할인 쿠폰").endsWith("12월 20일까지 쓸 수 있어요.");
    assertThat(comp.destinationType()).isEqualTo("ORDER");
    assertThat(comp.destinationRef()).isEqualTo("O-9");
    Notices.Notice signup =
        Notices.couponIssued("SIGNUP", null, null, "가입 축하 10%", "RATE", 10, "2026-12-20T15:00:00Z");
    assertThat(signup.title()).isEqualTo("가입 축하 쿠폰이 도착했어요");
    assertThat(signup.body()).startsWith("'가입 축하 10%' 쿠폰을 드렸어요.").endsWith("12월 21일까지 쓸 수 있어요.");
    assertThat(signup.destinationType()).isEqualTo("NONE");
  }

  /** CU-7(2026-11-23): 고객 정지 "7일 계정 정지"·이용제한·풀기(제한이 있던 경우만), 기한은 KST, 사유 없음. */
  @org.junit.jupiter.api.Test
  void customerRestrictionNotices() {
    java.time.Instant until = java.time.Instant.parse("2026-11-30T09:00:00Z");
    var s = Notices.customerRestriction("SUSPENDED", "NONE", until);
    org.assertj.core.api.Assertions.assertThat(s.templateKey()).isEqualTo("CUSTOMER_SUSPENDED");
    org.assertj.core.api.Assertions.assertThat(s.title()).isEqualTo("7일 계정 정지");
    org.assertj.core.api.Assertions.assertThat(s.body()).contains("11월 30일 18:00까지");
    org.assertj.core.api.Assertions.assertThat(
            Notices.customerRestriction("RESTRICTED", "NONE", until).templateKey())
        .isEqualTo("CUSTOMER_RESTRICTED");
    org.assertj.core.api.Assertions.assertThat(
            Notices.customerRestriction("NONE", "SUSPENDED", null).templateKey())
        .isEqualTo("CUSTOMER_RESTRICTION_LIFTED");
    org.assertj.core.api.Assertions.assertThat(Notices.customerRestriction("NONE", "NONE", null))
        .isNull();
  }
}
