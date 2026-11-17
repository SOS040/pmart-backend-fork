package com.pmart.review.api;

import com.pmart.review.api.generated.InternalApi;
import com.pmart.review.api.generated.model.GetReviewInternal200Response;
import com.pmart.review.api.generated.model.OrderReviewStatus;
import com.pmart.review.application.ReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — 서비스 간 리뷰 원문·상태·작성자 조회(삭제된 것 포함), 주문의 리뷰 작성 여부(0.3.0, 2026-11-18 — 리뷰 요청 알림). */
@RestController
public class InternalController implements InternalApi {

  private final ReviewService reviews;

  public InternalController(ReviewService reviews) {
    this.reviews = reviews;
  }

  @Override
  public ResponseEntity<GetReviewInternal200Response> getReviewInternal(String reviewId) {
    return ResponseEntity.ok(ReviewViews.internal(reviews.internal(reviewId)));
  }

  @Override
  public ResponseEntity<OrderReviewStatus> getOrderReviewStatus(String orderId, String accountId) {
    var s = reviews.orderReviewStatus(orderId, accountId);
    return ResponseEntity.ok(
        new OrderReviewStatus()
            .orderId(orderId)
            .accountId(accountId)
            .eligibleLineCount(s.eligibleLines())
            .reviewedLineCount(s.reviewedLines())
            .openLineCount(s.openLines())
            .reviewed(s.reviewedLines() > 0));
  }
}
