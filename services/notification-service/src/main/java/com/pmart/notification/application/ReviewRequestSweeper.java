package com.pmart.notification.application;

import com.pmart.common.web.PmartException;
import com.pmart.notification.domain.ReviewRequestRules;
import com.pmart.notification.infrastructure.ReviewClient;
import com.pmart.notification.infrastructure.ReviewRequestStore;
import com.pmart.notification.infrastructure.ReviewRequestStore.Pending;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 리뷰 요청 정리 작업(2026-11-18 사용자 결정 "A해줘"). 배송 완료 + {@code pmart.notification.review-request.delay} 가
 * 지난 대기 줄마다 review-service 에 그 주문의 리뷰를 썼는지 묻고:
 *
 * <ul>
 *   <li>썼으면(삭제 포함) SKIPPED_REVIEWED, 쓸 수 있는 품목이 없으면 SKIPPED_NOT_ELIGIBLE — 알림 없음.
 *   <li>아니면 같은 트랜잭션에서 SENT + 고객 알림함 {@code REVIEW_REQUESTED}(주문당 한 번, 앱 알림함만).
 *   <li>review-service 가 응답하지 않으면(연결 실패·시간 초과·5xx·이상한 답) PENDING 그대로 횟수만 올리고 이번 정리는 멈춘다(다음 정리 때
 *       다시).
 *   <li>완료 + {@code max-age} 가 지나도록 못 보낸 줄은 EXPIRED(보내지 않음).
 * </ul>
 *
 * <p>delay·max-age 가 없거나 review-service 주소가 없으면 아무 것도 하지 않는다(정책값을 지어내지 않는다 — CONFIG_REQUIRED 를 한 번
 * 기록). 여러 인스턴스가 같이 돌아도 줄 끝내기는 PENDING 조건부 갱신이고 알림은 dedupe_key 라 한 번만 생긴다.
 */
@Component
public class ReviewRequestSweeper {

  private static final Logger log = LoggerFactory.getLogger(ReviewRequestSweeper.class);

  /** 한 번에 고르는 줄 수와 한 정리의 최대 묶음 수(묶음이 꽉 차면 이어서). */
  static final int BATCH = 100;

  static final int MAX_BATCHES = 20;

  private final ReviewRequestStore store;
  private final ReviewClient reviews;
  private final NotificationService notifications;
  private final NotificationProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final AtomicBoolean configWarned = new AtomicBoolean();

  public ReviewRequestSweeper(
      ReviewRequestStore store,
      ReviewClient reviews,
      NotificationService notifications,
      NotificationProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.reviews = reviews;
    this.notifications = notifications;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  /** 한 정리의 결과(시험·로그용). */
  public record Result(int expired, int sent, int skipped, int failed, boolean configRequired) {}

  @Scheduled(
      initialDelayString = "${pmart.notification.review-request.sweep-initial-delay:PT1M}",
      fixedDelayString = "${pmart.notification.review-request.sweep-interval:PT5M}")
  void scheduled() {
    try {
      Result r = sweep();
      if (r.expired() + r.sent() + r.skipped() + r.failed() > 0) {
        log.info(
            "리뷰 요청 정리 — 보냄 {}, 안 보냄 {}, 포기 {}, 실패 {}",
            r.sent(),
            r.skipped(),
            r.expired(),
            r.failed());
      }
    } catch (RuntimeException e) {
      log.warn("리뷰 요청 정리 실패 — 다음에 다시: {}", e.toString());
    }
  }

  public Result sweep() {
    NotificationProperties.ReviewRequest cfg = props.reviewRequest();
    Duration delay = cfg == null ? null : cfg.delay();
    Duration maxAge = cfg == null ? null : cfg.maxAge();
    if (!ReviewRequestRules.configured(delay, maxAge) || !reviews.configured()) {
      if (configWarned.compareAndSet(false, true)) {
        log.warn(
            "리뷰 요청 CONFIG_REQUIRED — pmart.notification.review-request.delay·max-age·review-base-url 이"
                + " 없어 보내지 않음(대기 줄은 쌓인다)");
      }
      return new Result(0, 0, 0, 0, true);
    }
    configWarned.set(false);
    Instant now = now();
    int expired = store.expire(ReviewRequestRules.expiredBefore(now, maxAge), now);
    int sent = 0;
    int skipped = 0;
    int failed = 0;
    for (int b = 0; b < MAX_BATCHES; b++) {
      List<Pending> due = store.due(ReviewRequestRules.dueBefore(now, delay), BATCH);
      for (Pending p : due) {
        String outcome = process(p, now);
        if (outcome == null) {
          failed++;
          return new Result(expired, sent, skipped, failed, false); // review-service 문제 — 다음 정리 때
        }
        if (ReviewRequestRules.SENT.equals(outcome)) {
          sent++;
        } else {
          skipped++;
        }
      }
      if (due.size() < BATCH) {
        break;
      }
    }
    return new Result(expired, sent, skipped, failed, false);
  }

  /** 한 줄 처리. 끝낸 상태, review-service 를 못 물었으면 null(줄은 PENDING 그대로). */
  @Nullable String process(Pending p, Instant now) {
    ReviewClient.ReviewStatus status;
    try {
      status = reviews.status(p.orderId(), p.accountId());
    } catch (RuntimeException e) {
      String code = e instanceof PmartException pe ? pe.code() : e.getClass().getSimpleName();
      store.failed(p.orderId(), code, now);
      log.warn("리뷰 요청 — 주문 {} 의 리뷰 여부를 묻지 못함({}), 다음에 다시", p.orderId(), code);
      return null;
    }
    String outcome = ReviewRequestRules.decide(status.reviewed(), status.openLines());
    if (!ReviewRequestRules.SENT.equals(outcome)) {
      store.finish(p.orderId(), outcome, null, now);
      return outcome;
    }
    tx.executeWithoutResult(
        s -> {
          // 다른 인스턴스가 먼저 끝냈으면 알림을 만들지 않는다
          if (store.finish(p.orderId(), ReviewRequestRules.SENT, null, now)) {
            String id =
                notifications.notifyReviewRequested(
                    p.accountId(), p.orderId(), p.sourceEventId(), p.completedAt());
            if (id != null) {
              store.attachNotification(p.orderId(), id);
            }
          }
        });
    return outcome;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
