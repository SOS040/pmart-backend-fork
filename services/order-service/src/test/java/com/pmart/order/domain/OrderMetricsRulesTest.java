package com.pmart.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderMetricsRulesTest {

  static final LocalDate D = LocalDate.of(2026, 11, 1);

  @Test
  void rangeIsRequiredOrderedAndAtMost93DaysInclusive() {
    assertThat(OrderMetricsRules.rangeProblem(null, D)).isNotNull();
    assertThat(OrderMetricsRules.rangeProblem(D, null)).isNotNull();
    assertThat(OrderMetricsRules.rangeProblem(D, D.minusDays(1))).isNotNull();
    assertThat(OrderMetricsRules.rangeProblem(D, D)).isNull(); // 하루
    assertThat(OrderMetricsRules.rangeProblem(D, D.plusDays(92))).isNull(); // 93일
    assertThat(OrderMetricsRules.rangeProblem(D, D.plusDays(93))).isNotNull(); // 94일
  }

  @Test
  void rangeIsKoreanMidnightToNextKoreanMidnight() {
    OrderMetricsRules.Range r = OrderMetricsRules.range(D, D.plusDays(1));
    assertThat(r.from()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
    assertThat(r.toExclusive()).isEqualTo(Instant.parse("2026-11-02T15:00:00Z"));
  }

  @Test
  void limitAndRole() {
    assertThat(OrderMetricsRules.limitProblem(0)).isNotNull();
    assertThat(OrderMetricsRules.limitProblem(1)).isNull();
    assertThat(OrderMetricsRules.limitProblem(50)).isNull();
    assertThat(OrderMetricsRules.limitProblem(51)).isNotNull();
    assertThat(OrderMetricsRules.allowed(List.of("OPERATOR_ADMIN"))).isTrue();
    assertThat(OrderMetricsRules.allowed(List.of("CS_AGENT", "RIDER_OPS"))).isFalse();
  }
}
