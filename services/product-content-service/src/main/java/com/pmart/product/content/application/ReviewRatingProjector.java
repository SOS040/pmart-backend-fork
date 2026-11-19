package com.pmart.product.content.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.product.content.infrastructure.ReviewRatingStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * review 사건(commerce.review.v1) → 리뷰 별점 사본(2026-11-19). ReviewPublished(작성·수정 — 별점이 바뀔 수 있다)는 공개,
 * ReviewDeleted 는 삭제. 버전은 봉투의 aggregateVersion(리뷰 버전) — 큰 것만.
 */
@Service
public class ReviewRatingProjector {

  private static final Logger log = LoggerFactory.getLogger(ReviewRatingProjector.class);

  private final ReviewRatingStore store;
  private final Clock clock;

  public ReviewRatingProjector(ReviewRatingStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public void handle(EventEnvelope e) {
    boolean deleted = "ReviewDeleted".equals(e.eventType());
    if (!deleted && !"ReviewPublished".equals(e.eventType())) {
      return;
    }
    JsonNode p = e.payload();
    String reviewId = p.path("reviewId").asString(null);
    String productId = p.path("productId").asString(null);
    int rating = p.path("rating").asInt(0);
    if (reviewId == null || productId == null || rating < 1 || rating > 5) {
      log.warn("리뷰 사건 {} 값이 모자라 건너뜀", e.eventId());
      return;
    }
    store.upsert(reviewId, productId, rating, deleted, (int) e.aggregateVersion(), clock.instant());
  }
}
