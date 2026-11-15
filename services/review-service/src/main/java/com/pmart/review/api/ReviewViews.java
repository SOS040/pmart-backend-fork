package com.pmart.review.api;

import com.pmart.review.api.generated.model.Eligibility;
import com.pmart.review.api.generated.model.GetReviewInternal200Response;
import com.pmart.review.api.generated.model.ListMyReviewEligibilities200ResponsePage;
import com.pmart.review.api.generated.model.Review;
import com.pmart.review.api.generated.model.ReviewPhoto;
import com.pmart.review.api.generated.model.ReviewPhotosInner;
import com.pmart.review.api.generated.model.ReviewState;
import com.pmart.review.application.ReviewService.EligibilityItem;
import com.pmart.review.application.ReviewService.ReviewView;
import com.pmart.review.domain.ReviewRules;
import com.pmart.review.infrastructure.ReviewStore.PhotoRow;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/** 응답 변환. */
final class ReviewViews {

  private ReviewViews() {}

  static String photoUrl(String photoId) {
    return "/api/v1/review-photos/" + photoId;
  }

  static Review review(ReviewView v) {
    var r = v.row();
    return new Review()
        .reviewId(r.reviewId())
        .productId(r.productId())
        .sku(r.sku())
        .authorMasked(r.authorName())
        .rating(r.rating())
        .text(r.body())
        .photos(
            v.photos().stream()
                .map(p -> new ReviewPhotosInner().photoId(p.photoId()).url(photoUrl(p.photoId())))
                .toList())
        .state(ReviewState.fromValue(v.state()))
        .version(r.version())
        .createdAt(time(r.createdAt()))
        .editableUntil(time(ReviewRules.editableUntil(r.createdAt())))
        .updatedAt(time(r.updatedAt()));
  }

  static GetReviewInternal200Response internal(ReviewView v) {
    var r = v.row();
    return new GetReviewInternal200Response()
        .reviewId(r.reviewId())
        .productId(r.productId())
        .sku(r.sku())
        .authorMasked(r.authorName())
        .rating(r.rating())
        .text(r.body())
        .photos(
            v.photos().stream()
                .map(p -> new ReviewPhotosInner().photoId(p.photoId()).url(photoUrl(p.photoId())))
                .toList())
        .state(ReviewState.fromValue(v.state()))
        .version(r.version())
        .createdAt(time(r.createdAt()))
        .editableUntil(time(ReviewRules.editableUntil(r.createdAt())))
        .updatedAt(time(r.updatedAt()))
        .accountId(r.accountId())
        .orderLineId(r.orderLineId())
        .deliveredAt(time(v.deliveredAt()));
  }

  static Eligibility eligibility(EligibilityItem i) {
    var e = i.row();
    return new Eligibility()
        .orderId(e.orderId())
        .orderLineId(e.orderLineId())
        .sku(e.sku())
        .productId(e.productId())
        .productName(e.productName())
        .deliveredAt(time(e.deliveredAt()))
        .eligibleUntil(time(ReviewRules.eligibleUntil(e.deliveredAt())))
        .state(ReviewState.fromValue(i.state()))
        .reviewId(i.reviewId());
  }

  static ReviewPhoto photo(PhotoRow p) {
    return new ReviewPhoto()
        .photoId(p.photoId())
        .url(photoUrl(p.photoId()))
        .contentType(ReviewPhoto.ContentTypeEnum.fromValue(p.contentType()))
        .sizeBytes(p.sizeBytes())
        .uploadedAt(time(p.uploadedAt()));
  }

  static ListMyReviewEligibilities200ResponsePage page(@Nullable String next, int size) {
    return new ListMyReviewEligibilities200ResponsePage().nextCursor(next).size(size);
  }

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
