package com.pmart.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.claim.domain.ClaimRules.OrderLine;
import com.pmart.claim.domain.ClaimRules.Rejection;
import com.pmart.claim.domain.ClaimRules.Requested;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimRulesTest {

  final List<OrderLine> lines =
      List.of(new OrderLine("L1", "E-1", 1_900, 10, 10), new OrderLine("L2", "E-2", 1_000, 2, 2));

  static String code(Runnable r) {
    try {
      r.run();
      return "OK";
    } catch (Rejection e) {
      return e.code();
    }
  }

  @Test
  void fullCancelOnly() {
    // 모든 행 × 미이행 수량 → 통과
    assertThatCode(
            () ->
                ClaimRules.checkFullCancel(
                    "CONFIRMED", lines, List.of(new Requested("L1", 10), new Requested("L2", 2))))
        .doesNotThrowAnyException();
    // 일부 행·일부 수량 → 부분 취소 불가
    assertThat(
            code(
                () ->
                    ClaimRules.checkFullCancel(
                        "CONFIRMED", lines, List.of(new Requested("L1", 10)))))
        .isEqualTo("PARTIAL_CANCEL_NOT_ALLOWED");
    assertThat(
            code(
                () ->
                    ClaimRules.checkFullCancel(
                        "CONFIRMED",
                        lines,
                        List.of(new Requested("L1", 9), new Requested("L2", 2)))))
        .isEqualTo("PARTIAL_CANCEL_NOT_ALLOWED");
    // 미이행보다 많이·없는 행·같은 행 두 번
    assertThat(
            code(
                () ->
                    ClaimRules.checkFullCancel(
                        "CONFIRMED",
                        lines,
                        List.of(new Requested("L1", 11), new Requested("L2", 2)))))
        .isEqualTo("QUANTITY_EXCEEDS_AVAILABLE");
    assertThat(
            code(
                () ->
                    ClaimRules.checkFullCancel(
                        "CONFIRMED", lines, List.of(new Requested("LX", 1)))))
        .isEqualTo("QUANTITY_EXCEEDS_AVAILABLE");
    assertThat(
            code(
                () ->
                    ClaimRules.checkFullCancel(
                        "CONFIRMED",
                        lines,
                        List.of(new Requested("L1", 5), new Requested("L1", 5)))))
        .isEqualTo("QUANTITY_EXCEEDS_AVAILABLE");
    // 확정 상태가 아니면 취소 불가
    assertThat(
            code(
                () ->
                    ClaimRules.checkFullCancel(
                        "CANCELLED",
                        lines,
                        List.of(new Requested("L1", 10), new Requested("L2", 2)))))
        .isEqualTo("ORDER_NOT_CANCELLABLE");
    // 이미 전량 취소된 행은 요구하지 않는다
    assertThatCode(
            () ->
                ClaimRules.checkFullCancel(
                    "CONFIRMED",
                    List.of(
                        new OrderLine("L1", "E-1", 1_900, 10, 10),
                        new OrderLine("L2", "E-2", 1_000, 2, 0)),
                    List.of(new Requested("L1", 10))))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> ClaimRules.checkFullCancel("CONFIRMED", lines, List.of()))
        .isInstanceOf(Rejection.class);
  }

  @Test
  void fullRefundIsApprovedAmount() {
    // 상품 19,000 + 봉투 100 + 배달팁 0(첫 구매) = 승인 19,100 → 전액
    var a = ClaimRules.fullRefund(19_100, 19_000, 100, 0);
    assertThat(a).isNotNull();
    assertThat(a.amount()).isEqualTo(19_100);
    assertThat(a.bagFee()).isEqualTo(100);
    // 구성 합 ≠ 승인액 → 자동 환불하지 않는다
    assertThat(ClaimRules.fullRefund(19_200, 19_000, 100, 0)).isNull();
  }
}
