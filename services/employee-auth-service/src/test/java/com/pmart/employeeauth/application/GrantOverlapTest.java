package com.pmart.employeeauth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 중복 권한 판정(HR-2 안전망): 센터 겹침·지금 이후 기간 겹침. */
class GrantOverlapTest {

  static final Instant NOW = Instant.parse("2026-11-17T01:00:00Z");

  static GrantOverlap.Period p(long fromHours, Long untilHours) {
    return new GrantOverlap.Period(
        NOW.plusSeconds(fromHours * 3600),
        untilHours == null ? null : NOW.plusSeconds(untilHours * 3600));
  }

  @Test
  void centers() {
    assertThat(GrantOverlap.centersOverlap(true, List.of("A"), List.of("A", "B"))).isTrue();
    assertThat(GrantOverlap.centersOverlap(true, List.of("A"), List.of("B"))).isFalse();
    assertThat(GrantOverlap.centersOverlap(true, List.of(), List.of())).as("둘 다 센터 없음").isTrue();
    assertThat(GrantOverlap.centersOverlap(true, List.of(), List.of("A"))).isFalse();
    assertThat(GrantOverlap.centersOverlap(false, List.of("A"), List.of()))
        .as("센터 지정이 아닌 역할은 센터와 상관없이")
        .isTrue();
  }

  @Test
  void periodsFromNowOn() {
    assertThat(GrantOverlap.periodsOverlap(p(-10, 24L), p(-5, null), NOW)).isTrue();
    assertThat(GrantOverlap.periodsOverlap(p(-10, -1L), p(-20, null), NOW))
        .as("기존이 이미 끝남(회수 등)")
        .isFalse();
    assertThat(GrantOverlap.periodsOverlap(p(0, 24L), p(24, null), NOW))
        .as("끝나는 시각부터 시작")
        .isFalse();
    assertThat(GrantOverlap.periodsOverlap(p(48, 72L), p(0, null), NOW)).isTrue();
  }
}
