package com.pmart.delivery.capacity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlotRulesTest {

  static final LocalDate D = LocalDate.parse("2026-10-31");

  @Test
  void hourlyWindowsInsideOpenRangesNeverBeforeEight() {
    // 08:00~24:00 → 08-09 … 23-24 (16개)
    var w = SlotRules.windows(D, List.of(new int[] {480, 1440}));
    assertThat(w).hasSize(16);
    assertThat(w.get(0).start()).isEqualTo("08:00");
    assertThat(w.get(15).end()).isEqualTo("24:00");
    // 운영이 06:00 부터여도 08시 전 시간대 없음(07시 도착 슬롯 없음)
    assertThat(SlotRules.windows(D, List.of(new int[] {360, 600})))
        .extracting(SlotRules.Window::start)
        .containsExactly("08:00", "09:00");
    // 정시가 아닌 운영 시작·끝은 온전히 들어가는 시간대만
    assertThat(SlotRules.windows(D, List.of(new int[] {9 * 60 + 30, 12 * 60 + 15})))
        .extracting(SlotRules.Window::start)
        .containsExactly("10:00", "11:00");
  }

  @Test
  void availability() {
    Instant now = Instant.parse("2026-10-31T01:00:00Z");
    Instant later = now.plusSeconds(3600);
    assertThat(SlotRules.availability(null, 0, 0, later, now)).isEqualTo("CONFIG_REQUIRED");
    assertThat(SlotRules.availability(20, 0, 0, later, now)).isEqualTo("AVAILABLE");
    assertThat(SlotRules.availability(1, 1, 0, later, now)).isEqualTo("FULL");
    assertThat(SlotRules.availability(20, 0, 0, now, now)).isEqualTo("CLOSED");
  }

  @Test
  void startInstantIsSeoul() {
    var w = SlotRules.windows(D, List.of(new int[] {480, 600})).get(0);
    assertThat(w.startAt()).isEqualTo(Instant.parse("2026-10-30T23:00:00Z")); // 08:00 KST
  }
}
