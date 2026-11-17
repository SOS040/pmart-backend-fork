package com.pmart.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 리뷰 요청(2026-11-18): 기록 대상·결정·정책값 없음. */
class ReviewRequestRulesTest {

  @Test
  void recordsOnlyCustomerDeliveriesOfLiveOrders() {
    assertThat(ReviewRequestRules.records(null, "COMPLETED")).isTrue();
    assertThat(ReviewRequestRules.records("DELIVERY", "PARTIALLY_DELIVERED")).isTrue();
    assertThat(ReviewRequestRules.records("MISDELIVERY_REDELIVERY", null)).isTrue();
    assertThat(ReviewRequestRules.records("CLAIM_RETURN", "COMPLETED")).isFalse();
    assertThat(ReviewRequestRules.records("MISDELIVERY_RECOVERY", "COMPLETED")).isFalse();
    assertThat(ReviewRequestRules.records("FAILED_DELIVERY_RETURN", "COMPLETED")).isFalse();
    assertThat(ReviewRequestRules.records(null, "CANCELLED")).isFalse();
  }

  @Test
  void sendsOnlyWhenNothingReviewedAndSomethingOpen() {
    assertThat(ReviewRequestRules.decide(false, 2)).isEqualTo(ReviewRequestRules.SENT);
    assertThat(ReviewRequestRules.decide(true, 1)).isEqualTo(ReviewRequestRules.SKIPPED_REVIEWED);
    assertThat(ReviewRequestRules.decide(false, 0))
        .isEqualTo(ReviewRequestRules.SKIPPED_NOT_ELIGIBLE);
  }

  @Test
  void missingOrNegativePolicyIsNotConfigured() {
    assertThat(ReviewRequestRules.configured(Duration.ofHours(1), Duration.ofDays(3))).isTrue();
    assertThat(ReviewRequestRules.configured(null, Duration.ofDays(3))).isFalse();
    assertThat(ReviewRequestRules.configured(Duration.ofHours(1), null)).isFalse();
    assertThat(ReviewRequestRules.configured(Duration.ofHours(-1), Duration.ofDays(3))).isFalse();
  }

  @Test
  void dueAndExpiryCountFromCompletion() {
    Instant now = Instant.parse("2026-11-18T10:00:00Z");
    assertThat(ReviewRequestRules.dueBefore(now, Duration.ofHours(1)))
        .isEqualTo(Instant.parse("2026-11-18T09:00:00Z"));
    assertThat(ReviewRequestRules.expiredBefore(now, Duration.ofDays(3)))
        .isEqualTo(Instant.parse("2026-11-15T10:00:00Z"));
  }

  @Test
  void reviewRequestNoticeOpensOrderWithoutPersonalData() {
    var n = Notices.reviewRequested("ORD-1");
    assertThat(n.templateKey()).isEqualTo("REVIEW_REQUESTED");
    assertThat(n.category()).isEqualTo(Notices.ORDER);
    assertThat(n.destinationType()).isEqualTo("ORDER");
    assertThat(n.destinationRef()).isEqualTo("ORD-1");
    assertThat(n.body()).contains("14일");
  }
}
