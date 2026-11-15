package com.pmart.review.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewRulesTest {

  final Instant delivered = Instant.parse("2026-11-01T03:00:00Z");

  @Test
  void writeWindowIncludesTheFourteenDayBoundary() {
    Instant until = ReviewRules.eligibleUntil(delivered);
    assertThat(until).isEqualTo(Instant.parse("2026-11-15T03:00:00Z"));
    assertThat(ReviewRules.withinWriteWindow(delivered, until)).isTrue();
    assertThat(ReviewRules.withinWriteWindow(delivered, until.plusMillis(1))).isFalse();
  }

  @Test
  void editIsAllowedOnlyBeforeThreeDaysFromFirstRegistration() {
    Instant created = Instant.parse("2026-11-02T00:00:00Z");
    assertThat(ReviewRules.editable(created, created.plus(Duration.ofDays(3)).minusMillis(1)))
        .isTrue();
    assertThat(ReviewRules.editable(created, created.plus(Duration.ofDays(3)))).isFalse();
    assertThat(ReviewRules.reviewState("PUBLISHED", created, created.plus(Duration.ofDays(3))))
        .isEqualTo("LOCKED");
    assertThat(ReviewRules.reviewState("DELETED", created, created)).isEqualTo("DELETED");
  }

  @Test
  void eligibilityStateWithoutReviewDependsOnWindow() {
    assertThat(ReviewRules.eligibilityState(null, null, delivered, delivered))
        .isEqualTo("REV_ELIGIBLE");
    assertThat(
            ReviewRules.eligibilityState(
                null, null, delivered, delivered.plus(Duration.ofDays(15))))
        .isEqualTo("EXPIRED");
    // 리뷰가 있으면 기한이 지나도 리뷰 상태
    assertThat(
            ReviewRules.eligibilityState(
                "PUBLISHED", delivered, delivered, delivered.plus(Duration.ofDays(15))))
        .isEqualTo("LOCKED");
  }

  @Test
  void fullyReturnedOnlyWhenReturnedCoversDelivered() {
    assertThat(ReviewRules.fullyReturned(2, 2)).isTrue();
    assertThat(ReviewRules.fullyReturned(2, 1)).isFalse();
    assertThat(ReviewRules.fullyReturned(2, null)).isFalse(); // 모르면 막지 않는다
    assertThat(ReviewRules.fullyReturned(2, 0)).isFalse();
  }

  @Test
  void inputLimits() {
    assertThat(ReviewRules.inputProblem(null, true, null, null)).isPresent();
    assertThat(ReviewRules.inputProblem(null, false, null, null)).isEmpty();
    assertThat(ReviewRules.inputProblem(0, true, null, null)).isPresent();
    assertThat(ReviewRules.inputProblem(6, true, null, null)).isPresent();
    assertThat(ReviewRules.inputProblem(5, true, "가".repeat(1000), null)).isEmpty();
    assertThat(ReviewRules.inputProblem(5, true, "가".repeat(1001), null)).isPresent();
    assertThat(ReviewRules.inputProblem(5, true, null, List.of("a", "b", "c", "d", "e"))).isEmpty();
    assertThat(ReviewRules.inputProblem(5, true, null, List.of("a", "b", "c", "d", "e", "f")))
        .isPresent();
    assertThat(ReviewRules.inputProblem(5, true, null, List.of("a", "a"))).isPresent();
  }

  @Test
  void photoTypeIsSniffedFromBytes() {
    assertThat(ReviewRules.photoType(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}))
        .contains("image/jpeg");
    assertThat(
            ReviewRules.photoType(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
        .contains("image/png");
    assertThat(
            ReviewRules.photoType(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}))
        .contains("image/webp");
    assertThat(ReviewRules.photoType("GIF89a".getBytes())).isEmpty();
    assertThat(ReviewRules.photoProblem(new byte[0])).isPresent();
    byte[] big = new byte[ReviewRules.MAX_PHOTO_BYTES + 1];
    big[0] = (byte) 0xFF;
    big[1] = (byte) 0xD8;
    big[2] = (byte) 0xFF;
    assertThat(ReviewRules.photoProblem(big)).isPresent();
  }
}
