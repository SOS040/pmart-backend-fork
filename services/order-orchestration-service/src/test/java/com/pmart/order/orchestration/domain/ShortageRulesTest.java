package com.pmart.order.orchestration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 결품 사가 규칙(ADR-0045). */
class ShortageRulesTest {

  @Test
  void shortageIsAlwaysPartialCancelRegardlessOfPreference() {
    for (String p : new String[] {"CANCEL_OOS_ONLY", "CANCEL_ALL", null}) {
      assertThat(ShortageRules.escalationAtStart(p)).isNull();
      assertThat(ShortageRules.firstStep(p)).isEqualTo(ShortageRules.CHECK_ORDER);
    }
    assertThat(ShortageRules.next(ShortageRules.REQUEST_FULL_CANCEL)).isNull();
  }

  @Test
  void stepsInOrderAndWholeOrderCheck() {
    assertThat(ShortageRules.next(ShortageRules.CHECK_ORDER)).isEqualTo(ShortageRules.RESOLVE_WORK);
    assertThat(ShortageRules.next(ShortageRules.APPLY_ORDER_CHANGE))
        .isEqualTo(ShortageRules.REQUEST_REFUND);
    assertThat(ShortageRules.next(ShortageRules.REQUEST_REFUND)).isNull();
    assertThat(ShortageRules.wholeOrder(5, 5)).isTrue();
    assertThat(ShortageRules.wholeOrder(5, 1)).isFalse();
  }
}
