package com.pmart.review.api;

import com.pmart.review.api.generated.EligibilitiesApi;
import com.pmart.review.api.generated.model.ListMyReviewEligibilities200Response;
import com.pmart.review.api.generated.model.ReviewState;
import com.pmart.review.application.ReviewService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 EligibilitiesApi — 본인 리뷰 자격(배송 완료 품목). */
@RestController
public class EligibilitiesController implements EligibilitiesApi {

  private final ReviewService reviews;

  public EligibilitiesController(ReviewService reviews) {
    this.reviews = reviews;
  }

  @Override
  public ResponseEntity<ListMyReviewEligibilities200Response> listMyReviewEligibilities(
      @Nullable String orderId,
      @Nullable ReviewState state,
      @Nullable Integer size,
      @Nullable String cursor) {
    int n = size == null ? 50 : size;
    var p = reviews.eligibilities(orderId, state == null ? null : state.getValue(), cursor, n);
    return ResponseEntity.ok(
        new ListMyReviewEligibilities200Response()
            .items(
                p.items().stream()
                    .map(
                        i ->
                            ReviewViews.eligibility(i)
                                // 0.6.1 BR-86 — 그 주문의 쓸 수 있는 품목 수(쪽과 상관없이)
                                .orderWritableCount(
                                    p.orderWritable().getOrDefault(i.row().orderId(), 0)))
                    .toList())
            .page(ReviewViews.page(p.nextCursor(), n))
            .total(p.counts().total())
            .counts(ReviewViews.counts(p.counts())));
  }
}
