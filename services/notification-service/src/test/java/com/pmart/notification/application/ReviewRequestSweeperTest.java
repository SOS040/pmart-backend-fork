package com.pmart.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pmart.common.web.PmartException;
import com.pmart.notification.domain.ReviewRequestRules;
import com.pmart.notification.infrastructure.ReviewClient;
import com.pmart.notification.infrastructure.ReviewRequestStore;
import com.pmart.notification.infrastructure.ReviewRequestStore.Pending;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 리뷰 요청 정리(2026-11-18): 정책값·주소 없으면 아무 것도 안 함, 안 썼으면 보냄, 썼으면 안 보냄, review-service 실패면 PENDING 그대로
 * 멈춤.
 */
class ReviewRequestSweeperTest {

  static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

  final ReviewRequestStore store = mock(ReviewRequestStore.class);
  final ReviewClient reviews = mock(ReviewClient.class);
  final NotificationService notifications = mock(NotificationService.class);
  final TransactionTemplate tx = new DirectTx();

  /** 트랜잭션 없이 바로 실행. */
  static class DirectTx extends TransactionTemplate {
    @Override
    public <T> T execute(TransactionCallback<T> action) {
      return action.doInTransaction(null);
    }
  }

  ReviewRequestSweeper sweeper(Duration delay, Duration maxAge) {
    return new ReviewRequestSweeper(
        store,
        reviews,
        notifications,
        new NotificationProperties(
            null,
            null,
            null,
            "http://review",
            new NotificationProperties.ReviewRequest(delay, maxAge)),
        tx,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  Pending pending(String orderId) {
    return new Pending(orderId, "ACC-1", NOW.minus(Duration.ofHours(2)), "evt-1");
  }

  @Test
  void missingPolicyOrAddressSendsNothing() {
    when(reviews.configured()).thenReturn(true);
    var r = sweeper(null, Duration.ofDays(3)).sweep();
    assertThat(r.configRequired()).isTrue();
    when(reviews.configured()).thenReturn(false);
    assertThat(sweeper(Duration.ofHours(1), Duration.ofDays(3)).sweep().configRequired()).isTrue();
    verify(store, never()).due(any(), anyInt());
    verify(store, never()).expire(any(), any());
  }

  @Test
  void notReviewedSendsOnceAndReviewedSkips() {
    when(reviews.configured()).thenReturn(true);
    when(store.due(NOW.minus(Duration.ofHours(1)), ReviewRequestSweeper.BATCH))
        .thenReturn(List.of(pending("ORD-A"), pending("ORD-B")));
    when(reviews.status("ORD-A", "ACC-1")).thenReturn(new ReviewClient.ReviewStatus(false, 2));
    when(reviews.status("ORD-B", "ACC-1")).thenReturn(new ReviewClient.ReviewStatus(true, 1));
    when(store.finish(anyString(), anyString(), any(), any())).thenReturn(true);
    when(notifications.notifyReviewRequested(
            "ACC-1", "ORD-A", "evt-1", pending("ORD-A").completedAt()))
        .thenReturn("NTF-1");

    var r = sweeper(Duration.ofHours(1), Duration.ofDays(3)).sweep();

    assertThat(r.sent()).isEqualTo(1);
    assertThat(r.skipped()).isEqualTo(1);
    verify(store).expire(NOW.minus(Duration.ofDays(3)), NOW);
    verify(store).finish("ORD-A", ReviewRequestRules.SENT, null, NOW);
    verify(store).attachNotification("ORD-A", "NTF-1");
    verify(store).finish("ORD-B", ReviewRequestRules.SKIPPED_REVIEWED, null, NOW);
    verify(notifications, never())
        .notifyReviewRequested(eq("ACC-1"), eq("ORD-B"), anyString(), any());
  }

  @Test
  void alreadyFinishedElsewhereDoesNotNotify() {
    when(reviews.configured()).thenReturn(true);
    when(store.due(any(), anyInt())).thenReturn(List.of(pending("ORD-A")));
    when(reviews.status("ORD-A", "ACC-1")).thenReturn(new ReviewClient.ReviewStatus(false, 1));
    when(store.finish(anyString(), anyString(), any(), any())).thenReturn(false);

    sweeper(Duration.ofHours(1), Duration.ofDays(3)).sweep();

    verify(notifications, never())
        .notifyReviewRequested(anyString(), anyString(), anyString(), any());
  }

  @Test
  void reviewServiceDownKeepsPendingAndStops() {
    when(reviews.configured()).thenReturn(true);
    when(store.due(any(), anyInt())).thenReturn(List.of(pending("ORD-A"), pending("ORD-B")));
    when(reviews.status("ORD-A", "ACC-1"))
        .thenThrow(PmartException.dependencyUnavailable("review-service", new RuntimeException()));

    var r = sweeper(Duration.ofHours(1), Duration.ofDays(3)).sweep();

    assertThat(r.failed()).isEqualTo(1);
    verify(store).failed("ORD-A", "DEPENDENCY_UNAVAILABLE", NOW);
    verify(reviews, never()).status("ORD-B", "ACC-1"); // 이번 정리는 멈춘다
    verify(store, never()).finish(anyString(), anyString(), any(), any());
  }
}
