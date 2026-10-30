package com.pmart.mfc.capacity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CapacityRulesTest {

  @Test
  void windowIsOneHourAlignedToSeoulHour() {
    // 10:00~11:00 KST = 01:00Z~02:00Z. 오프셋 표기가 달라도 같은 창
    var w = CapacityRules.window("2026-10-31T01:00:00Z/2026-10-31T02:00:00Z");
    assertThat(w.start()).isEqualTo(Instant.parse("2026-10-31T01:00:00Z"));
    assertThat(w.text()).isEqualTo("2026-10-31T01:00:00Z/2026-10-31T02:00:00Z");
    assertThat(CapacityRules.window("2026-10-31T10:00:00+09:00/2026-10-31T11:00:00+09:00"))
        .isEqualTo(w);
  }

  @Test
  void rejectsOtherIntervals() {
    for (String bad :
        Arrays.asList(
            null,
            "",
            "2026-10-31T01:00:00Z",
            "2026-10-31T01:00:00Z/2026-10-31T03:00:00Z", // 2시간
            "2026-10-31T01:30:00Z/2026-10-31T02:30:00Z", // 정시 아님
            "2026-10-31T02:00:00Z/2026-10-31T01:00:00Z", // 거꾸로
            "2026-10-31T01:00:00/2026-10-31T02:00:00", // 오프셋 없음
            "a/b")) {
      assertThatThrownBy(() -> CapacityRules.window(bad))
          .isInstanceOfSatisfying(
              PmartException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
    }
  }

  @Test
  void capacityNotSetIsConfigRequired() {
    assertThatThrownBy(() -> CapacityRules.requireCapacity(null))
        .isInstanceOfSatisfying(
            PmartException.class, e -> assertThat(e.code()).isEqualTo("CONFIG_REQUIRED"));
    assertThat(CapacityRules.requireCapacity(30)).isEqualTo(30);
  }

  @Test
  void oneOrderIsOneUnitAndFits() {
    assertThat(CapacityRules.unitsPerOrder()).isEqualTo(1);
    assertThat(CapacityRules.fits(30, 20, 9, 1)).isTrue();
    assertThat(CapacityRules.fits(30, 20, 10, 1)).isFalse();
    assertThat(CapacityRules.available(30, 20, 5)).isEqualTo(5);
    assertThat(CapacityRules.available(10, 8, 5)).isZero(); // 정책값을 줄여도 음수 없음
  }

  @Test
  void expiredHeldIsEffectivelyExpired() {
    Instant exp = Instant.parse("2026-10-31T01:10:00Z");
    var h =
        new CapacityHold(
            "CH-1",
            "O-1",
            "MFC-1",
            CapacityRules.window("2026-10-31T01:00:00Z/2026-10-31T02:00:00Z"),
            1,
            CapacityHold.State.HELD,
            1,
            exp,
            null);
    assertThat(h.effectiveState(exp.minusSeconds(1))).isEqualTo(CapacityHold.State.HELD);
    assertThat(h.effectiveState(exp)).isEqualTo(CapacityHold.State.EXPIRED);
  }
}
