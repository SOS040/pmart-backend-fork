package com.pmart.review.api;

import com.pmart.review.api.generated.ReviewsApi;
import com.pmart.review.api.generated.model.CreateReviewRequest;
import com.pmart.review.api.generated.model.ListProductReviews200Response;
import com.pmart.review.api.generated.model.ListProductReviews200ResponseSummary;
import com.pmart.review.api.generated.model.Review;
import com.pmart.review.api.generated.model.UpdateReviewRequest;
import com.pmart.review.application.ReviewService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ReviewsApi — 작성·수정·삭제(본인)·공개 조회. */
@RestController
public class ReviewsController implements ReviewsApi {

  private final ReviewService reviews;

  public ReviewsController(ReviewService reviews) {
    this.reviews = reviews;
  }

  @Override
  public ResponseEntity<Review> createReview(
      String idempotencyKey, String xSourceSystem, CreateReviewRequest body) {
    var v =
        reviews.create(
            idempotencyKey,
            xSourceSystem,
            new ReviewService.CreateCommand(
                body.getOrderLineId(),
                body.getRating(),
                body.getText(),
                Objects.requireNonNullElse(body.getPhotoIds(), List.of())));
    return ResponseEntity.status(HttpStatus.CREATED).body(ReviewViews.review(v));
  }

  @Override
  public ResponseEntity<Review> updateReview(
      String reviewId,
      String idempotencyKey,
      String xSourceSystem,
      UpdateReviewRequest body,
      @Nullable Integer xExpectedVersion) {
    var v =
        reviews.update(
            reviewId,
            idempotencyKey,
            xSourceSystem,
            new ReviewService.UpdateCommand(body.getRating(), body.getText(), body.getPhotoIds()),
            xExpectedVersion);
    return ResponseEntity.ok(ReviewViews.review(v));
  }

  @Override
  public ResponseEntity<Void> deleteReview(
      String reviewId, String idempotencyKey, String xSourceSystem) {
    reviews.delete(reviewId, idempotencyKey, xSourceSystem);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Review> getReview(String reviewId) {
    return ResponseEntity.ok(ReviewViews.review(reviews.get(reviewId)));
  }

  @Override
  public ResponseEntity<ListProductReviews200Response> listProductReviews(
      String productId,
      @Nullable String sort,
      @Nullable Boolean withPhotoOnly,
      @Nullable Integer size,
      @Nullable String cursor) {
    int n = size == null ? 50 : size;
    var p = reviews.productReviews(productId, sort, Boolean.TRUE.equals(withPhotoOnly), cursor, n);
    return ResponseEntity.ok(
        new ListProductReviews200Response()
            .items(p.items().stream().map(ReviewViews::review).toList())
            .page(ReviewViews.page(p.nextCursor(), n))
            .summary(
                new ListProductReviews200ResponseSummary()
                    .averageRating(
                        p.average() == null
                            ? null
                            : BigDecimal.valueOf(p.average()).setScale(1, RoundingMode.HALF_UP))
                    .reviewCount(p.count())));
  }
}
