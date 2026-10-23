package com.pmart.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class SeoulDatesTest {

  @Test
  void seoulDateCrossesUtcMidnight() {
    // 2026-10-23 16:00 UTC = 10-24 01:00 Asia/Seoul
    Instant at = Instant.parse("2026-10-23T16:00:00Z");
    assertThat(SeoulDates.toLocalDate(at)).isEqualTo(LocalDate.of(2026, 10, 24));
  }

  @Test
  void calendarMonthClampsToEndOfMonth() {
    Instant jan31 = SeoulDates.at(LocalDate.of(2026, 1, 31), LocalTime.of(10, 0));
    Instant plus1 = SeoulDates.plusCalendarMonths(jan31, 1);
    assertThat(SeoulDates.toLocalDate(plus1)).isEqualTo(LocalDate.of(2026, 2, 28));
    Instant plus3 = SeoulDates.plusCalendarMonths(jan31, 3);
    assertThat(SeoulDates.toLocalDate(plus3)).isEqualTo(LocalDate.of(2026, 4, 30));
  }

  @Test
  void returnWindowIsInclusiveAtBoundary() {
    Instant received = Instant.parse("2026-10-01T05:30:00Z");
    Instant deadline = SeoulDates.plusDays(received, 7);
    assertThat(SeoulDates.isWithinInclusive(deadline, deadline)).isTrue();
    assertThat(SeoulDates.isWithinInclusive(deadline.plusSeconds(1), deadline)).isFalse();
  }

  @Test
  void expiryDateBlocksFromSeoulMidnight() {
    LocalDate expiry = LocalDate.of(2026, 10, 24);
    Instant justBefore = Instant.parse("2026-10-23T14:59:59Z"); // 23:59:59 KST 10-23
    Instant atMidnight = Instant.parse("2026-10-23T15:00:00Z"); // 00:00 KST 10-24
    assertThat(SeoulDates.isExpiredOn(expiry, justBefore)).isFalse();
    assertThat(SeoulDates.isExpiredOn(expiry, atMidnight)).isTrue();
  }

  @Test
  void endOfDayExclusiveEquals24h() {
    LocalDate d = LocalDate.of(2026, 10, 20);
    assertThat(SeoulDates.endOfDayExclusive(d)).isEqualTo(Instant.parse("2026-10-20T15:00:00Z"));
    assertThat(
            SeoulDates.isBeforeExclusive(
                SeoulDates.endOfDayExclusive(d), SeoulDates.endOfDayExclusive(d)))
        .isFalse();
  }
}
