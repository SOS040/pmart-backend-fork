package com.pmart.review.api;

import com.pmart.review.api.generated.ReviewReportsApi;
import com.pmart.review.api.generated.model.ListReviewReports200Response;
import com.pmart.review.api.generated.model.ReportReview201Response;
import com.pmart.review.api.generated.model.ResolveReviewReportsRequest;
import com.pmart.review.api.generated.model.ReviewReportGroup;
import com.pmart.review.application.ReviewReports;
import com.pmart.review.domain.ReviewRules;
import com.pmart.review.infrastructure.ReviewReportStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ReviewReportsApi — 운영자 리뷰 신고 확인(0.7.0 BR-90, CS_AGENT·OPERATOR_ADMIN). */
@RestController
public class ReviewReportsController implements ReviewReportsApi {

  private final ReviewReports reports;

  public ReviewReportsController(ReviewReports reports) {
    this.reports = reports;
  }

  @Override
  public ResponseEntity<ListReviewReports200Response> listReviewReports(
      String state, Integer size, @Nullable String cursor) {
    var p = reports.groups(state == null ? "OPEN" : state, size == null ? 20 : size, cursor);
    return ResponseEntity.ok(
        new ListReviewReports200Response()
            .items(p.items().stream().map(ReviewReportsController::group).toList())
            .nextCursor(p.nextCursor()));
  }

  @Override
  public ResponseEntity<ReportReview201Response> resolveReviewReports(
      String reviewId,
      String idempotencyKey,
      String xSourceSystem,
      ResolveReviewReportsRequest body) {
    String id =
        reports.resolve(
            reviewId, body.getAction().getValue(), body.getNote(), idempotencyKey, xSourceSystem);
    return ResponseEntity.ok(
        new ReportReview201Response()
            .status(ReportReview201Response.StatusEnum.APPLIED)
            .requestId(idempotencyKey)
            .resultId(id));
  }

  private static ReviewReportGroup group(ReviewReportStore.Group g) {
    return new ReviewReportGroup()
        .reviewId(g.reviewId())
        .productId(g.productId())
        .sku(g.sku())
        .rating(g.rating())
        .text(g.body())
        .authorMasked(g.authorName())
        .reviewCreatedAt(time(g.reviewCreatedAt()))
        .reviewHidden(ReviewRules.HIDDEN.equals(g.reviewState()))
        .reviewDeleted(ReviewRules.DELETED.equals(g.reviewState()))
        .state(
            g.resolution() == null
                ? ReviewReportGroup.StateEnum.OPEN
                : ReviewReportGroup.StateEnum.RESOLVED)
        .reportCount(g.count())
        .reasonCounts(g.reasonCounts())
        .otherDetails(g.otherDetails())
        .firstReportedAt(time(g.firstReportedAt()))
        .lastReportedAt(time(g.lastReportedAt()))
        .resolution(
            g.resolution() == null
                ? null
                : ReviewReportGroup.ResolutionEnum.fromValue(g.resolution()))
        .resolvedAt(g.resolvedAt() == null ? null : time(g.resolvedAt()))
        .resolvedBy(g.resolvedBy());
  }

  private static OffsetDateTime time(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
