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
  void selectableUsesListingRules() {
    List<int[]> open = List.of(new int[] {360, 1440});
    Instant before = Instant.parse("2026-10-30T15:00:00Z"); // 10-31 00:00 KST
    assertThat(SlotRules.selectable(SlotRules.parseWindow(D, "13:00", "14:00"), open, before))
        .isTrue();
    // 07시 도착(08시 전) · 정시 아님 · 2시간 · 운영 밖 은 선택 불가
    assertThat(SlotRules.selectable(SlotRules.parseWindow(D, "07:00", "08:00"), open, before))
        .isFalse();
    assertThat(SlotRules.selectable(SlotRules.parseWindow(D, "08:30", "09:30"), open, before))
        .isFalse();
    assertThat(SlotRules.selectable(SlotRules.parseWindow(D, "09:00", "11:00"), open, before))
        .isFalse();
    assertThat(
            SlotRules.selectable(
                SlotRules.parseWindow(D, "13:00", "14:00"), List.of(new int[] {480, 720}), before))
        .isFalse();
    // 시작 시각(마감) 이후
    Instant atStart = Instant.parse("2026-10-31T04:00:00Z"); // 13:00 KST
    assertThat(SlotRules.selectable(SlotRules.parseWindow(D, "13:00", "14:00"), open, atStart))
        .isFalse();
  }

  @Test
  void bookableDateTodayOrTomorrowSeoul() {
    Instant now = Instant.parse("2026-10-30T16:00:00Z"); // 10-31 01:00 KST
    assertThat(SlotRules.bookableDate(D, now)).isTrue();
    assertThat(SlotRules.bookableDate(D.plusDays(1), now)).isTrue();
    assertThat(SlotRules.bookableDate(D.minusDays(1), now)).isFalse();
    assertThat(SlotRules.bookableDate(D.plusDays(2), now)).isFalse();
  }

  @Test
  void parseWindowRejectsBadFormat() {
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> SlotRules.parseWindow(D, "8:00", "9:00"));
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> SlotRules.parseWindow(D, "24:00", "24:00"));
    assertThat(SlotRules.parseWindow(D, "23:00", "24:00").endMinute()).isEqualTo(1440);
  }

  @Test
  void startInstantIsSeoul() {
    var w = SlotRules.windows(D, List.of(new int[] {480, 600})).get(0);
    assertThat(w.startAt()).isEqualTo(Instant.parse("2026-10-30T23:00:00Z")); // 08:00 KST
  }
}
