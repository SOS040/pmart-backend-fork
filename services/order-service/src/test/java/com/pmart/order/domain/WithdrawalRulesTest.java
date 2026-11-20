package com.pmart.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 고객 탈퇴 의무 확인 규칙(2026-11-20, ADR-0077, 계약 0.16.0). */
class WithdrawalRulesTest {

  @ParameterizedTest
  @ValueSource(strings = {"COMPLETED", "CANCELLED", "EXPIRED"})
  void finishedOrdersAreNotObligations(String status) {
    assertThat(WithdrawalRules.orderObligation(status)).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CONFIRMED",
        "PREPARING",
        "PARTIALLY_HANDED_OVER",
        "IN_DELIVERY",
        "PARTIALLY_DELIVERED",
        "DRAFT",
        "RESOURCE_PENDING",
        "PAYMENT_PENDING",
        "SOMETHING_NEW"
      })
  void unfinishedOrdersAreInProgress(String status) {
    // 모르는 상태도 끝났다고 추정하지 않는다
    assertThat(WithdrawalRules.orderObligation(status))
        .isEqualTo(WithdrawalRules.ORDER_IN_PROGRESS);
  }

  @Test
  void ledgerPaymentUnknownIsPaymentUnknown() {
    assertThat(WithdrawalRules.orderObligation("PAYMENT_UNKNOWN"))
        .isEqualTo(WithdrawalRules.PAYMENT_UNKNOWN);
  }

  @Test
  void compensatedCheckoutIsNotAnObligation() {
    assertThat(WithdrawalRules.checkoutObligation("COMPENSATED")).isNull();
  }

  @Test
  void completedSagaWithoutLedgerOrderIsInProgress() {
    assertThat(WithdrawalRules.checkoutObligation("COMPLETED"))
        .isEqualTo(WithdrawalRules.ORDER_IN_PROGRESS);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "RUNNING",
        "WAITING_RESULT",
        "UNKNOWN_TRACKING",
        "COMPENSATING",
        "RECONCILIATION_REQUIRED",
        "ESCALATED",
        "SOMETHING_NEW"
      })
  void openOrUnknownCheckoutIsPaymentUnknown(String state) {
    assertThat(WithdrawalRules.checkoutObligation(state))
        .isEqualTo(WithdrawalRules.PAYMENT_UNKNOWN);
  }

  @Test
  void sagaLookupFailureIsPaymentUnknown() {
    assertThat(WithdrawalRules.checkoutObligation(null)).isEqualTo(WithdrawalRules.PAYMENT_UNKNOWN);
  }
}
