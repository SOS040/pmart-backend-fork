package com.pmart.mfc.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.fulfillment.domain.HandoverRules.AuthorizeFacts;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HandoverRulesTest {

  static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");

  static AuthorizeFacts facts(
      String pkgState, boolean stopped, String assignedRider, boolean live, boolean requested) {
    return new AuthorizeFacts(
        true,
        pkgState,
        "READY",
        stopped,
        "S1",
        "S1",
        "ASSIGNED",
        assignedRider,
        "R1",
        live,
        requested,
        "R2",
        "D1",
        "D1");
  }

  @Test
  void expiredFenceEndsOnlyWithoutCommitRequest() {
    Instant past = NOW.minusSeconds(1);
    assertThat(HandoverRules.fenceLive("HANDOVER_IN_PROGRESS", past, false, NOW)).isFalse();
    assertThat(HandoverRules.fenceLive("HANDOVER_IN_PROGRESS", past, true, NOW)).isTrue();
    assertThat(HandoverRules.fenceLive("HANDOVER_IN_PROGRESS", NOW.plusSeconds(60), false, NOW))
        .isTrue();
    assertThat(HandoverRules.fenceLive("EXPIRED", NOW.plusSeconds(60), true, NOW)).isFalse();
  }

  @Test
  void authorizeOrderOfChecks() {
    assertThat(HandoverRules.authorize(facts("HANDED_OVER", false, "R1", false, false)).code())
        .isEqualTo(HandoverRules.ALREADY_HANDED_OVER);
    assertThat(HandoverRules.authorize(facts("INVALIDATED", false, "R1", false, false)).code())
        .isEqualTo(HandoverRules.PACKAGE_INVALIDATED);
    assertThat(HandoverRules.authorize(facts("SEALED", true, "R1", false, false)).code())
        .isEqualTo(HandoverRules.INVALID_STATE);
    assertThat(HandoverRules.authorize(facts("SEALED", false, "R9", false, false)).code())
        .isEqualTo(HandoverRules.RIDER_MISMATCH);
    assertThat(HandoverRules.authorize(facts("SEALED", false, "R1", true, true)).code())
        .isEqualTo(HandoverRules.HANDOVER_RESULT_PENDING);
    // 살아 있는 다른 라이더 fence(R2) → FENCE_ACTIVE, 없으면 새 허가
    assertThat(HandoverRules.authorize(facts("SEALED", false, "R1", true, false)).code())
        .isEqualTo(HandoverRules.FENCE_ACTIVE);
    assertThat(HandoverRules.authorize(facts("SEALED", false, "R1", false, false)).outcome())
        .isEqualTo("NEW");
  }

  @Test
  void verdictPerPackage() {
    assertThat(HandoverRules.verdict("HANDED_OVER", false))
        .isEqualTo(HandoverRules.Verdict.HANDED_OVER);
    assertThat(HandoverRules.verdict("SEALED", true))
        .isEqualTo(HandoverRules.Verdict.HANDOVER_IN_PROGRESS);
    assertThat(HandoverRules.verdict("INVALIDATED", false))
        .isEqualTo(HandoverRules.Verdict.STOPPED);
    assertThat(HandoverRules.verdict("SEALED", false)).isEqualTo(HandoverRules.Verdict.READY);
  }
}
