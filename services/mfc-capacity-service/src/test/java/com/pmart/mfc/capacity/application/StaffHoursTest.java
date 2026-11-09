package com.pmart.mfc.capacity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class StaffHoursTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void rangesBecomeSeoulInstantsAnd2400IsNextMidnight() {
    var w =
        StaffHours.windowsOf(
            LocalDate.parse("2026-11-09"),
            mapper.readTree(
                "{\"open\":true,\"ranges\":[{\"start\":\"07:00\",\"end\":\"12:00\"},{\"start\":\"13:00\",\"end\":\"24:00\"}]}"));
    assertThat(w).hasSize(2);
    assertThat(w.get(0)[0]).isEqualTo(Instant.parse("2026-11-08T22:00:00Z"));
    assertThat(w.get(0)[1]).isEqualTo(Instant.parse("2026-11-09T03:00:00Z"));
    assertThat(w.get(1)[1]).isEqualTo(Instant.parse("2026-11-09T15:00:00Z"));
  }

  @Test
  void holidayOrClosedDayHasNoHours() {
    assertThat(
            StaffHours.windowsOf(
                LocalDate.parse("2026-11-09"),
                mapper.readTree(
                    "{\"open\":true,\"isHoliday\":true,\"ranges\":[{\"start\":\"07:00\",\"end\":\"24:00\"}]}")))
        .isEmpty();
    assertThat(
            StaffHours.windowsOf(
                LocalDate.parse("2026-11-09"), mapper.readTree("{\"open\":false,\"ranges\":[]}")))
        .isEmpty();
  }
}
