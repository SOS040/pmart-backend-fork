package com.pmart.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.claim.domain.RefundMetricsRules.Bucket;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundMetricsRulesTest {

  static final LocalDate D = LocalDate.of(2026, 11, 1);

  @Test
  void refundReasonsFallIntoFixedBuckets() {
    assertThat(RefundMetricsRules.bucket("CANCEL_BEFORE_SHIP")).isEqualTo(Bucket.CANCEL);
    assertThat(RefundMetricsRules.bucket("OUT_OF_STOCK")).isEqualTo(Bucket.SHORTAGE);
    assertThat(RefundMetricsRules.bucket("RETURN")).isEqualTo(Bucket.RETURN);
    assertThat(RefundMetricsRules.bucket("RETURN_FEE")).isEqualTo(Bucket.RETURN_FEE);
    assertThat(RefundMetricsRules.bucket("SOMETHING_NEW")).isEqualTo(Bucket.OTHER);
    assertThat(RefundMetricsRules.bucket(null)).isEqualTo(Bucket.OTHER);
  }

  @Test
  void rangeIsRequiredOrderedAndAtMost93DaysInclusive() {
    assertThat(RefundMetricsRules.rangeProblem(null, D)).isNotNull();
    assertThat(RefundMetricsRules.rangeProblem(D, null)).isNotNull();
    assertThat(RefundMetricsRules.rangeProblem(D, D.minusDays(1))).isNotNull();
    assertThat(RefundMetricsRules.rangeProblem(D, D)).isNull();
    assertThat(RefundMetricsRules.rangeProblem(D, D.plusDays(92))).isNull();
    assertThat(RefundMetricsRules.rangeProblem(D, D.plusDays(93))).isNotNull();
    RefundMetricsRules.Range r = RefundMetricsRules.range(D, D);
    assertThat(r.from()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
    assertThat(r.toExclusive()).isEqualTo(Instant.parse("2026-11-01T15:00:00Z"));
  }

  @Test
  void onlyOperatorAdmin() {
    assertThat(RefundMetricsRules.allowed(List.of("OPERATOR_ADMIN"))).isTrue();
    assertThat(RefundMetricsRules.allowed(List.of("CS_AGENT"))).isFalse();
  }
}
