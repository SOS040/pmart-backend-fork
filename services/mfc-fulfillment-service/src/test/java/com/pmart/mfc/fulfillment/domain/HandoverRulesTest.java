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
        "D1",
        live);
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
    // 만료됐지만 확인 없는 다른 라이더(R2) 허가 → 점원 확인 전 거절(D-1 안 C)
    AuthorizeFacts open = facts("SEALED", false, "R1", false, false);
    AuthorizeFacts unconfirmed =
        new AuthorizeFacts(
            open.qrMatches(),
            open.packageState(),
            open.shipmentState(),
            open.orderStopped(),
            open.assignmentShipmentId(),
            open.packageShipmentId(),
            open.assignmentState(),
            open.assignedRiderId(),
            open.riderId(),
            false,
            false,
            "R2",
            "D1",
            "D1",
            true);
    assertThat(HandoverRules.authorize(unconfirmed).code())
        .isEqualTo(HandoverRules.FENCE_EXPIRED_UNCONFIRMED);
    // 같은 라이더·배송이면 확인 없이 새 허가
    AuthorizeFacts sameRider =
        new AuthorizeFacts(
            true,
            "SEALED",
            "READY",
            false,
            "S1",
            "S1",
            "ASSIGNED",
            "R1",
            "R1",
            false,
            false,
            "R1",
            "D1",
            "D1",
            true);
    assertThat(HandoverRules.authorize(sameRider).outcome()).isEqualTo("NEW");
    Instant past = NOW.minusSeconds(1);
    assertThat(HandoverRules.checkRequired("HANDOVER_IN_PROGRESS", past, false, NOW)).isTrue();
    assertThat(HandoverRules.checkRequired("HANDOVER_IN_PROGRESS", past, true, NOW)).isFalse();
    assertThat(HandoverRules.checkRequired("HANDOVER_IN_PROGRESS", NOW.plusSeconds(9), false, NOW))
        .isFalse();
    assertThat(HandoverRules.checkRequired("EXPIRED", past, false, NOW)).isFalse();
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
