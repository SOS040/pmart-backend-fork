package com.pmart.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** N-4 — order-orchestration 재발송 기준(ReshipmentRules.reshipsOnFailure)과 같은지. */
class DeliveryFailureRulesTest {

  @Test
  void incidentAndDamageOnForwardOrReshipmentNotify() {
    assertThat(DeliveryFailureRules.noticeReshipment("FORWARD", null, "RIDER_INCIDENT")).isTrue();
    assertThat(DeliveryFailureRules.noticeReshipment("FORWARD", null, "DAMAGED")).isTrue();
    assertThat(
            DeliveryFailureRules.noticeReshipment(
                "RESHIPMENT", "MISDELIVERY_RESHIPMENT", "RIDER_INCIDENT"))
        .isTrue();
  }

  @Test
  void otherReasonsAndDeliveriesDoNotNotify() {
    assertThat(DeliveryFailureRules.noticeReshipment("FORWARD", null, "CUSTOMER_ABSENT")).isFalse();
    assertThat(DeliveryFailureRules.noticeReshipment("FORWARD", null, null)).isFalse();
    assertThat(
            DeliveryFailureRules.noticeReshipment(
                "RESHIPMENT", "MISDELIVERY_REDELIVERY", "RIDER_INCIDENT"))
        .as("오배송 다시 배송 — 원래 상품")
        .isFalse();
    assertThat(DeliveryFailureRules.noticeReshipment("RETURN", "CLAIM_RETURN", "DAMAGED"))
        .isFalse();
    assertThat(DeliveryFailureRules.noticeReshipment(null, null, "DAMAGED")).isFalse();
  }
}
