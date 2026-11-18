package com.pmart.claim.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.claim.domain.RefundMetricsRules.Bucket;
import com.pmart.claim.infrastructure.RefundMetricsStore.ReasonRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefundMetricsServiceTest {

  @Test
  void groupsReasonsIntoAllFiveBucketsInOrder() {
    RefundMetricsService.Result r =
        RefundMetricsService.group(
            List.of(
                new ReasonRow("RETURN", 2, 7_000),
                new ReasonRow("CANCEL_BEFORE_SHIP", 1, 19_100),
                new ReasonRow("NEW_A", 1, 10),
                new ReasonRow("NEW_B", 2, 20)));
    assertThat(r.totalAmount()).isEqualTo(26_130);
    assertThat(r.totalCount()).isEqualTo(6);
    assertThat(r.byBucket())
        .containsExactly(
            new RefundMetricsService.Line(Bucket.CANCEL, 19_100, 1),
            new RefundMetricsService.Line(Bucket.SHORTAGE, 0, 0),
            new RefundMetricsService.Line(Bucket.RETURN, 7_000, 2),
            new RefundMetricsService.Line(Bucket.RETURN_FEE, 0, 0),
            new RefundMetricsService.Line(Bucket.OTHER, 30, 3));
  }
}
