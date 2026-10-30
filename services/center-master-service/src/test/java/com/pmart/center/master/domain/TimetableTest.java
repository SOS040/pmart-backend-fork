package com.pmart.center.master.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TimetableTest {

  static final List<String[]> EIGHT_TO_MIDNIGHT =
      List.<String[]>of(new String[] {"08:00", "24:00"});

  static Timetable everyDay(Set<LocalDate> holidays, List<Timetable.Restriction> restrictions) {
    return new Timetable(
        Timetable.weekly(
            Map.of(
                "MON",
                EIGHT_TO_MIDNIGHT,
                "TUE",
                EIGHT_TO_MIDNIGHT,
                "WED",
                EIGHT_TO_MIDNIGHT,
                "THU",
                EIGHT_TO_MIDNIGHT,
                "FRI",
                EIGHT_TO_MIDNIGHT,
                "SAT",
                EIGHT_TO_MIDNIGHT,
                "SUN",
                EIGHT_TO_MIDNIGHT)),
        holidays,
        restrictions);
  }

  @Test
  void openRangesWithMidnightEnd() {
    Timetable t = everyDay(Set.of(), List.of());
    Timetable.Day d = t.day(LocalDate.parse("2026-10-31"));
    assertThat(d.open()).isTrue();
    assertThat(d.ranges()).extracting(Timetable.Range::end).containsExactly("24:00");
    assertThat(t.openAt(ZonedDateTime.parse("2026-10-31T07:59:00+09:00"))).isFalse();
    assertThat(t.openAt(ZonedDateTime.parse("2026-10-31T08:00:00+09:00"))).isTrue();
    assertThat(t.openAt(ZonedDateTime.parse("2026-10-31T23:59:00+09:00"))).isTrue();
    // UTC 로 주어도 서울 기준(22:30Z = 07:30 KST)
    assertThat(t.openAt(ZonedDateTime.parse("2026-10-30T22:30:00Z"))).isFalse();
  }

  @Test
  void holidayAndRestriction() {
    LocalDate holiday = LocalDate.parse("2026-11-01");
    LocalDate restricted = LocalDate.parse("2026-11-02");
    Timetable t =
        everyDay(
            Set.of(holiday),
            List.of(
                new Timetable.Restriction(restricted, Timetable.Range.of("12:00", "14:00"), "점검")));
    assertThat(t.day(holiday).open()).isFalse();
    assertThat(t.day(holiday).holiday()).isTrue();
    assertThat(t.day(restricted).ranges())
        .extracting(r -> r.start() + "-" + r.end())
        .containsExactly("08:00-12:00", "14:00-24:00");
    assertThat(t.windows(LocalDate.parse("2026-10-31"), 3)).hasSize(3); // 31일 1 + 1일 휴일 0 + 2일 2
  }

  @Test
  void rejectsBadRanges() {
    assertThatThrownBy(() -> Timetable.Range.of("09:00", "08:00"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Timetable.Range.of("8:00", "09:00"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Timetable.Range.of("08:00", "24:30"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                Timetable.weekly(
                    Map.of(
                        "MON",
                        List.of(new String[] {"08:00", "12:00"}, new String[] {"11:00", "13:00"}))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
