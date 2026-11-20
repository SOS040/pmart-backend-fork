package com.pmart.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.claim.domain.WithdrawalRules.ClaimFacts;
import com.pmart.claim.domain.WithdrawalRules.Obligation;
import java.util.List;
import org.junit.jupiter.api.Test;

class WithdrawalRulesTest {

  static ClaimFacts c(String id, String state, List<String> refunds, String fee) {
    return new ClaimFacts(id, state, refunds, fee);
  }

  @Test
  void noClaimsOrAllSettledIsEligible() {
    assertThat(WithdrawalRules.openObligations(List.of())).isEmpty();
    assertThat(
            WithdrawalRules.openObligations(
                List.of(
                    c("C1", "CLOSED", List.of("COMPLETED"), null),
                    c("C2", "REJECTED", List.of(), null),
                    c("C3", "CANCELLED", List.of(), "UNPAID"),
                    c("C4", "CANCELLED", List.of("COMPLETED"), "PAID"),
                    c("C5", "WITHDRAWN", List.of(), null),
                    c("C6", "CANCELLED", List.of(), "LATE_CANCELLED"))))
        .isEmpty();
  }

  @Test
  void nonTerminalClaimStatesAreOpenIncludingUnknownOnes() {
    for (String s :
        List.of("STOP_PENDING", "IN_PROGRESS", "FEE_PENDING", "RECOVERY_FAILED", "SOMETHING_NEW")) {
      assertThat(WithdrawalRules.openObligations(List.of(c("C1", s, List.of(), null))))
          .as(s)
          .containsExactly(new Obligation(WithdrawalRules.CLAIM_IN_PROGRESS, "C1"));
    }
  }

  @Test
  void anyRefundNotCompletedIsOpenEvenWhenClaimClosed() {
    for (String r : List.of("PENDING_EXECUTION", "PG_PENDING", "UNKNOWN", "FAILED")) {
      assertThat(
              WithdrawalRules.openObligations(
                  List.of(c("C1", "CANCELLED", List.of("COMPLETED", r), "PAID"))))
          .as(r)
          .containsExactly(new Obligation(WithdrawalRules.REFUND_IN_PROGRESS, "C1"));
    }
  }

  @Test
  void openClaimWithPendingRefundReportsBoth() {
    assertThat(
            WithdrawalRules.openObligations(
                List.of(
                    c("C1", "CLOSED", List.of("COMPLETED"), null),
                    c("C2", "IN_PROGRESS", List.of("PG_PENDING"), null))))
        .containsExactly(
            new Obligation(WithdrawalRules.CLAIM_IN_PROGRESS, "C2"),
            new Obligation(WithdrawalRules.REFUND_IN_PROGRESS, "C2"));
  }

  @Test
  void returnFeeStillOwedIsRefundInProgress() {
    assertThat(
            WithdrawalRules.openObligations(
                List.of(
                    c("C1", "CANCELLED", List.of(), "LATE_CANCEL_PENDING"),
                    c("C2", "CANCELLED", List.of(), "LATE_CANCEL_FAILED"),
                    c("C3", "CANCELLED", List.of(), "WAITING"))))
        .containsExactly(
            new Obligation(WithdrawalRules.REFUND_IN_PROGRESS, "C1"),
            new Obligation(WithdrawalRules.REFUND_IN_PROGRESS, "C2"),
            new Obligation(WithdrawalRules.REFUND_IN_PROGRESS, "C3"));
    // 진행 중 반품의 회수비 결제 대기는 환불이 아니다 — 클레임 미결만
    assertThat(
            WithdrawalRules.openObligations(List.of(c("C4", "FEE_PENDING", List.of(), "WAITING"))))
        .containsExactly(new Obligation(WithdrawalRules.CLAIM_IN_PROGRESS, "C4"));
  }
}
