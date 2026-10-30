package com.pmart.delivery.capacity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SlotHoldTest {

  static final Instant T0 = Instant.parse("2026-10-31T01:00:00Z");

  static SlotHold hold(SlotHold.State state) {
    return new SlotHold(
        "SH-1",
        1,
        "ORD-1",
        null,
        null,
        "MFC-1",
        new SlotRules.Window(LocalDate.parse("2026-10-31"), 13 * 60, 14 * 60),
        state,
        T0,
        T0.plusSeconds(600),
        null,
        null,
        null);
  }

  @Test
  void heldPastExpiryIsSeenAsExpired() {
    SlotHold h = hold(SlotHold.State.HELD);
    assertThat(h.stateAt(T0.plusSeconds(599))).isEqualTo(SlotHold.State.HELD);
    assertThat(h.stateAt(T0.plusSeconds(600))).isEqualTo(SlotHold.State.EXPIRED);
    assertThat(h.observedAt(T0.plusSeconds(601)).state()).isEqualTo(SlotHold.State.EXPIRED);
    assertThat(h.slotId()).isEqualTo("MFC-1:2026-10-31:13:00");
  }

  @Test
  void confirmDecisions() {
    assertThat(hold(SlotHold.State.HELD).confirmDecision(T0)).isEqualTo(SlotHold.Decision.APPLY);
    assertThat(hold(SlotHold.State.CONFIRMED).confirmDecision(T0))
        .isEqualTo(SlotHold.Decision.ALREADY_DONE);
    assertThat(hold(SlotHold.State.RELEASED).confirmDecision(T0))
        .isEqualTo(SlotHold.Decision.INVALID);
    assertThat(hold(SlotHold.State.EXPIRED).confirmDecision(T0))
        .isEqualTo(SlotHold.Decision.INVALID);
    // 만료 시각이 지난 HELD 는 확정할 수 없다(정원에서 이미 빠짐)
    assertThat(hold(SlotHold.State.HELD).confirmDecision(T0.plusSeconds(600)))
        .isEqualTo(SlotHold.Decision.INVALID);
  }

  @Test
  void releaseDecisions() {
    assertThat(hold(SlotHold.State.HELD).releaseDecision(T0)).isEqualTo(SlotHold.Decision.APPLY);
    assertThat(hold(SlotHold.State.CONFIRMED).releaseDecision(T0))
        .isEqualTo(SlotHold.Decision.APPLY);
    assertThat(hold(SlotHold.State.RELEASED).releaseDecision(T0))
        .isEqualTo(SlotHold.Decision.ALREADY_DONE);
    assertThat(hold(SlotHold.State.EXPIRED).releaseDecision(T0))
        .isEqualTo(SlotHold.Decision.ALREADY_DONE);
  }

  @Test
  void operatorReleaseNeedsApproval() {
    assertThat(SlotHold.approvalMissing("OPERATOR", null)).isTrue();
    assertThat(SlotHold.approvalMissing("OPERATOR", " ")).isTrue();
    assertThat(SlotHold.approvalMissing("OPERATOR", "APR-1")).isFalse();
    assertThat(SlotHold.approvalMissing("SAGA_COMPENSATION", null)).isFalse();
  }
}
