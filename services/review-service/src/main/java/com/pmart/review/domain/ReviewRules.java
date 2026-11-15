package com.pmart.review.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 리뷰 규칙(Commerce 15장 확정 정책 + 2026-11-16 사용자 결정, ADR-0063).
 *
 * <ul>
 *   <li>작성: 품목별 실제 수령 + 14일 경계까지(그 시각 포함), 품목당 1건, 삭제 뒤 재작성 불가.
 *   <li>수정: 최초 등록 + 3일 미만(수정이 기한을 늘리지 않는다). 지나면 LOCKED(공개 유지).
 *   <li>사진: 리뷰당 5장, 장당 10MB, JPEG·PNG·WEBP(파일 앞부분으로 판정).
 *   <li>그 품목이 받은 수량만큼 모두 반품되었으면 새로 쓰지 못한다(이미 쓴 리뷰는 그대로).
 * </ul>
 */
public final class ReviewRules {

  public static final Duration WRITE_WINDOW = Duration.ofDays(14);
  public static final Duration EDIT_WINDOW = Duration.ofDays(3);
  public static final int MAX_PHOTOS = 5;
  public static final int MAX_PHOTO_BYTES = 10 * 1024 * 1024;
  public static final int MAX_TEXT = 1000;

  /** 리뷰에 붙지 않은(또는 수정으로 떨어진) 사진을 지우기까지. */
  public static final Duration ORPHAN_PHOTO_TTL = Duration.ofHours(24);

  public static final String PUBLISHED = "PUBLISHED";
  public static final String DELETED = "DELETED";
  public static final String LOCKED = "LOCKED";
  public static final String ELIGIBLE = "REV_ELIGIBLE";
  public static final String EXPIRED = "EXPIRED";

  private ReviewRules() {}

  public static Instant eligibleUntil(Instant deliveredAt) {
    return deliveredAt.plus(WRITE_WINDOW);
  }

  /** 작성 기한 안인가(경계 포함). */
  public static boolean withinWriteWindow(Instant deliveredAt, Instant now) {
    return !now.isAfter(eligibleUntil(deliveredAt));
  }

  public static Instant editableUntil(Instant createdAt) {
    return createdAt.plus(EDIT_WINDOW);
  }

  /** 수정 가능한가(최초 등록 + 3일 미만). */
  public static boolean editable(Instant createdAt, Instant now) {
    return now.isBefore(editableUntil(createdAt));
  }

  /** 받은 수량이 모두 반품되었는가(반품 수량을 모르면 아니다). */
  public static boolean fullyReturned(int deliveredQty, @Nullable Integer returnedQty) {
    return returnedQty != null && deliveredQty > 0 && returnedQty >= deliveredQty;
  }

  /** 자격 목록의 계약 상태: 리뷰가 없으면 작성 가능·기한 지남, 있으면 리뷰 상태. */
  public static String eligibilityState(
      @Nullable String reviewState, @Nullable Instant createdAt, Instant deliveredAt, Instant now) {
    if (reviewState == null || createdAt == null) {
      return withinWriteWindow(deliveredAt, now) ? ELIGIBLE : EXPIRED;
    }
    return reviewState(reviewState, createdAt, now);
  }

  /** 리뷰의 계약 상태(PUBLISHED·LOCKED·DELETED). */
  public static String reviewState(String stored, Instant createdAt, Instant now) {
    if (DELETED.equals(stored)) {
      return DELETED;
    }
    return editable(createdAt, now) ? PUBLISHED : LOCKED;
  }

  /** 입력 문제(없으면 빈 값). 별점은 ratingRequired 일 때 필수. */
  public static Optional<String> inputProblem(
      @Nullable Integer rating,
      boolean ratingRequired,
      @Nullable String text,
      @Nullable List<String> photoIds) {
    if (rating == null ? ratingRequired : (rating < 1 || rating > 5)) {
      return Optional.of("별점은 1~5 입니다.");
    }
    if (text != null && text.codePointCount(0, text.length()) > MAX_TEXT) {
      return Optional.of("내용은 " + MAX_TEXT + "자까지입니다.");
    }
    if (photoIds != null) {
      if (photoIds.size() > MAX_PHOTOS) {
        return Optional.of("사진은 " + MAX_PHOTOS + "장까지입니다.");
      }
      if (photoIds.stream().anyMatch(id -> id == null || id.isBlank())) {
        return Optional.of("사진 ID 가 비어 있습니다.");
      }
      if (photoIds.stream().distinct().count() != photoIds.size()) {
        return Optional.of("같은 사진을 두 번 붙일 수 없습니다.");
      }
    }
    return Optional.empty();
  }

  /** 파일 앞부분으로 형식 판정(이름·고객이 보낸 Content-Type 은 믿지 않는다). JPEG·PNG·WEBP 만. */
  public static Optional<String> photoType(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return Optional.of("image/jpeg");
    }
    if (b.length >= 8
        && (b[0] & 0xFF) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && b[4] == 0x0D
        && b[5] == 0x0A
        && b[6] == 0x1A
        && b[7] == 0x0A) {
      return Optional.of("image/png");
    }
    if (b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') {
      return Optional.of("image/webp");
    }
    return Optional.empty();
  }

  /** 사진 크기·형식 문제(없으면 빈 값). */
  public static Optional<String> photoProblem(byte[] b) {
    if (b.length == 0) {
      return Optional.of("빈 파일입니다.");
    }
    if (b.length > MAX_PHOTO_BYTES) {
      return Optional.of("사진은 장당 10MB 까지입니다.");
    }
    if (photoType(b).isEmpty()) {
      return Optional.of("JPEG·PNG·WEBP 사진만 올릴 수 있습니다.");
    }
    return Optional.empty();
  }
}
