package com.pmart.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 배송 지연 신고(0.23.0 — 2026-11-18 사용자 "A해줘"): 주문이 확정 뒤 ~ 배송 완료 전일 때만. */
class InquiryRulesDeliveryDelayTest {

  @Test
  void delayIsAcceptedWhileOrderIsInProgress() {
    for (String s :
        new String[] {
          "CONFIRMED", "PREPARING", "PARTIALLY_HANDED_OVER", "IN_DELIVERY", "PARTIALLY_DELIVERED"
        }) {
      assertThat(InquiryRules.deliveryIssueAllowed("DELAYED", s)).as(s).isTrue();
    }
  }

  @Test
  void delayIsRejectedBeforeConfirmationAfterCompletionOrWhenStatusUnknown() {
    for (String s :
        new String[] {
          "COMPLETED", "CANCELLED", "EXPIRED", "DRAFT", "PAYMENT_PENDING", "PAYMENT_UNKNOWN", "X"
        }) {
      assertThat(InquiryRules.deliveryIssueAllowed("DELAYED", s)).as(s).isFalse();
    }
    assertThat(InquiryRules.deliveryIssueAllowed("DELAYED", null)).isFalse();
  }

  @Test
  void otherIssuesAndPlainInquiriesDoNotLookAtStatus() {
    assertThat(InquiryRules.deliveryIssueAllowed("NOT_RECEIVED", "COMPLETED")).isTrue();
    assertThat(InquiryRules.deliveryIssueAllowed("MISDELIVERED", null)).isTrue();
    assertThat(InquiryRules.deliveryIssueAllowed(null, "CANCELLED")).isTrue();
  }
}
