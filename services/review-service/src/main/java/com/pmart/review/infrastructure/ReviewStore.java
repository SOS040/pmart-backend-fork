package com.pmart.review.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 리뷰 표(V2 — {@code review_eligibility}·{@code review}·{@code review_photo}). */
@Repository
public class ReviewStore {

  public record EligibilityRow(
      String orderLineId,
      String orderId,
      String accountId,
      @Nullable String sku,
      @Nullable String productId,
      @Nullable String productName,
      int deliveredQty,
      Instant deliveredAt,
      @Nullable String deliveryId) {}

  /** 자격 목록 한 줄(리뷰가 있으면 그 상태·등록 시각). */
  public record EligibilityView(
      EligibilityRow e,
      @Nullable String reviewId,
      @Nullable String reviewState,
      @Nullable Instant reviewCreatedAt) {}

  public record ReviewRow(
      String reviewId,
      String orderLineId,
      String orderId,
      String accountId,
      String productId,
      @Nullable String sku,
      String authorName,
      int rating,
      @Nullable String body,
      String state,
      int version,
      Instant createdAt,
      @Nullable Instant updatedAt,
      @Nullable Instant deletedAt) {}

  public record PhotoRow(
      String photoId,
      String accountId,
      String objectKey,
      String contentType,
      long sizeBytes,
      String sha256,
      Instant uploadedAt,
      @Nullable String reviewId) {}

  public record Summary(@Nullable Double average, int count) {}

  private final JdbcTemplate jdbc;

  public ReviewStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------------------------------------------------------------- 자격

  /**
   * 수령 사실 반영: 처음이면 행을 만들고, 이미 있으면 수량을 더하고 수령 시각은 이른 것을 둔다(분할 수령 — 첫 수령부터 14일). 같은 사건의 재전달은 inbox 가
   * 막는다.
   */
  public void upsertEligibility(EligibilityRow r, Instant now) {
    jdbc.update(
        "INSERT INTO review_eligibility (order_line_id, order_id, account_id, sku, product_id,"
            + " product_name, delivered_qty, delivered_at, delivery_id, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (order_line_id) DO UPDATE SET"
            + " delivered_qty = review_eligibility.delivered_qty + EXCLUDED.delivered_qty,"
            + " delivered_at = LEAST(review_eligibility.delivered_at, EXCLUDED.delivered_at)",
        r.orderLineId(),
        r.orderId(),
        r.accountId(),
        r.sku(),
        r.productId(),
        r.productName(),
        r.deliveredQty(),
        ts(r.deliveredAt()),
        r.deliveryId(),
        ts(now));
  }

  public Optional<EligibilityRow> eligibility(String orderLineId) {
    return jdbc
        .query(
            "SELECT * FROM review_eligibility WHERE order_line_id = ?",
            (rs, i) -> eligibilityRow(rs),
            orderLineId)
        .stream()
        .findFirst();
  }

  public Optional<EligibilityRow> lockEligibility(String orderLineId) {
    return jdbc
        .query(
            "SELECT * FROM review_eligibility WHERE order_line_id = ? FOR UPDATE",
            (rs, i) -> eligibilityRow(rs),
            orderLineId)
        .stream()
        .findFirst();
  }

  /** 본인 자격 목록(최근 수령 순). state 는 계약 상태(시각으로 계산) — SQL 에서 같은 식으로 걸러 페이지가 어긋나지 않게 한다. */
  public List<EligibilityView> eligibilities(
      String accountId,
      @Nullable String orderId,
      @Nullable String state,
      Instant now,
      int offset,
      int limit) {
    String stateExpr =
        "CASE WHEN r.review_id IS NULL THEN"
            + " CASE WHEN e.delivered_at + interval '14 days' >= ? THEN 'REV_ELIGIBLE' ELSE 'EXPIRED' END"
            + " WHEN r.state = 'DELETED' THEN 'DELETED'"
            + " WHEN r.created_at + interval '3 days' > ? THEN 'PUBLISHED' ELSE 'LOCKED' END";
    StringBuilder sql =
        new StringBuilder(
            "SELECT * FROM (SELECT e.*, r.review_id AS r_id, r.state AS r_state,"
                + " r.created_at AS r_created_at, "
                + stateExpr
                + " AS computed_state FROM review_eligibility e"
                + " LEFT JOIN review r ON r.order_line_id = e.order_line_id"
                + " WHERE e.account_id = ?");
    List<Object> args = new ArrayList<>(List.of(ts(now), ts(now), accountId));
    if (orderId != null) {
      sql.append(" AND e.order_id = ?");
      args.add(orderId);
    }
    sql.append(") x");
    if (state != null) {
      sql.append(" WHERE computed_state = ?");
      args.add(state);
    }
    sql.append(" ORDER BY delivered_at DESC, order_line_id LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new EligibilityView(
                eligibilityRow(rs),
                rs.getString("r_id"),
                rs.getString("r_state"),
                instant(rs, "r_created_at")),
        args.toArray());
  }

  // ---------------------------------------------------------------- 리뷰

  public Optional<ReviewRow> review(String reviewId) {
    return jdbc
        .query("SELECT * FROM review WHERE review_id = ?", (rs, i) -> reviewRow(rs), reviewId)
        .stream()
        .findFirst();
  }

  public Optional<ReviewRow> lockReview(String reviewId) {
    return jdbc
        .query(
            "SELECT * FROM review WHERE review_id = ? FOR UPDATE",
            (rs, i) -> reviewRow(rs),
            reviewId)
        .stream()
        .findFirst();
  }

  public Optional<ReviewRow> reviewOfLine(String orderLineId) {
    return jdbc
        .query(
            "SELECT * FROM review WHERE order_line_id = ?", (rs, i) -> reviewRow(rs), orderLineId)
        .stream()
        .findFirst();
  }

  public void insertReview(ReviewRow r) {
    jdbc.update(
        "INSERT INTO review (review_id, order_line_id, order_id, account_id, product_id, sku,"
            + " author_name, rating, body, state, version, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        r.reviewId(),
        r.orderLineId(),
        r.orderId(),
        r.accountId(),
        r.productId(),
        r.sku(),
        r.authorName(),
        r.rating(),
        r.body(),
        r.state(),
        r.version(),
        ts(r.createdAt()));
  }

  public void updateReview(
      String reviewId, int rating, @Nullable String body, int version, Instant now) {
    jdbc.update(
        "UPDATE review SET rating = ?, body = ?, version = ?, updated_at = ? WHERE review_id = ?",
        rating,
        body,
        version,
        ts(now),
        reviewId);
  }

  public void markDeleted(String reviewId, int version, Instant now) {
    jdbc.update(
        "UPDATE review SET state = 'DELETED', version = ?, deleted_at = ?, updated_at = ?"
            + " WHERE review_id = ?",
        version,
        ts(now),
        ts(now),
        reviewId);
  }

  /** 상품 공개 리뷰(PUBLISHED — LOCKED 도 저장은 PUBLISHED). */
  public List<ReviewRow> productReviews(
      String productId, String sort, boolean withPhotoOnly, int offset, int limit) {
    String order =
        switch (sort) {
          case "RATING_DESC" -> "rating DESC, created_at DESC, review_id";
          case "RATING_ASC" -> "rating ASC, created_at DESC, review_id";
          default -> "created_at DESC, review_id";
        };
    return jdbc.query(
        "SELECT * FROM review r WHERE product_id = ? AND state = 'PUBLISHED'"
            + (withPhotoOnly
                ? " AND EXISTS (SELECT 1 FROM review_photo p WHERE p.review_id = r.review_id)"
                : "")
            + " ORDER BY "
            + order
            + " LIMIT ? OFFSET ?",
        (rs, i) -> reviewRow(rs),
        productId,
        limit,
        offset);
  }

  public Summary summary(String productId) {
    return jdbc.queryForObject(
        "SELECT avg(rating)::float8 AS a, count(*) AS c FROM review"
            + " WHERE product_id = ? AND state = 'PUBLISHED'",
        (rs, i) -> {
          double a = rs.getDouble("a");
          return new Summary(rs.wasNull() ? null : a, rs.getInt("c"));
        },
        productId);
  }

  // ---------------------------------------------------------------- 사진

  public void insertPhoto(PhotoRow p) {
    jdbc.update(
        "INSERT INTO review_photo (photo_id, account_id, object_key, content_type, size_bytes,"
            + " sha256, uploaded_at) VALUES (?,?,?,?,?,?,?)",
        p.photoId(),
        p.accountId(),
        p.objectKey(),
        p.contentType(),
        p.sizeBytes(),
        p.sha256(),
        ts(p.uploadedAt()));
  }

  public Optional<PhotoRow> photo(String photoId) {
    return jdbc
        .query("SELECT * FROM review_photo WHERE photo_id = ?", (rs, i) -> photoRow(rs), photoId)
        .stream()
        .findFirst();
  }

  /** 리뷰에 붙은 사진(순서대로). */
  public List<PhotoRow> photosOf(String reviewId) {
    return jdbc.query(
        "SELECT * FROM review_photo WHERE review_id = ? ORDER BY sort_order",
        (rs, i) -> photoRow(rs),
        reviewId);
  }

  /** 여러 리뷰의 사진을 한 번에. */
  public List<PhotoRow> photosOf(List<String> reviewIds) {
    if (reviewIds.isEmpty()) {
      return List.of();
    }
    String in = String.join(",", java.util.Collections.nCopies(reviewIds.size(), "?"));
    return jdbc.query(
        "SELECT * FROM review_photo WHERE review_id IN (" + in + ") ORDER BY review_id, sort_order",
        (rs, i) -> photoRow(rs),
        reviewIds.toArray());
  }

  /** 사진을 리뷰에 붙인다: 이 계정이 올렸고 아직 어디에도 붙지 않았거나 이미 이 리뷰에 붙은 것만. 붙인 장수를 돌려준다(요청 수와 다르면 호출자가 거절한다). */
  public int attachPhotos(String reviewId, String accountId, List<String> photoIds) {
    int n = 0;
    for (int i = 0; i < photoIds.size(); i++) {
      n +=
          jdbc.update(
              "UPDATE review_photo SET review_id = ?, sort_order = ?, detached_at = NULL"
                  + " WHERE photo_id = ? AND account_id = ? AND (review_id IS NULL OR review_id = ?)",
              reviewId,
              i,
              photoIds.get(i),
              accountId,
              reviewId);
    }
    return n;
  }

  /** 수정으로 빠진 사진을 떼어 정리 대상으로. */
  public void detachPhotosExcept(String reviewId, List<String> keep, Instant now) {
    List<Object> args = new ArrayList<>();
    args.add(ts(now));
    args.add(reviewId);
    String notIn = "";
    if (!keep.isEmpty()) {
      notIn =
          " AND photo_id NOT IN ("
              + String.join(",", java.util.Collections.nCopies(keep.size(), "?"))
              + ")";
      args.addAll(keep);
    }
    jdbc.update(
        "UPDATE review_photo SET review_id = NULL, sort_order = NULL, detached_at = ?"
            + " WHERE review_id = ?"
            + notIn,
        args.toArray());
  }

  /** 정리 대상: 붙지 않은 채 오래된 사진, 수정으로 떨어진 지 오래된 사진. */
  public List<PhotoRow> orphanPhotos(Instant before, int limit) {
    return jdbc.query(
        "SELECT * FROM review_photo WHERE review_id IS NULL"
            + " AND COALESCE(detached_at, uploaded_at) < ? ORDER BY uploaded_at LIMIT ?",
        (rs, i) -> photoRow(rs),
        ts(before),
        limit);
  }

  public void deletePhoto(String photoId) {
    jdbc.update("DELETE FROM review_photo WHERE photo_id = ? AND review_id IS NULL", photoId);
  }

  // ---------------------------------------------------------------- 행 변환

  private static EligibilityRow eligibilityRow(ResultSet rs) throws SQLException {
    return new EligibilityRow(
        rs.getString("order_line_id"),
        rs.getString("order_id"),
        rs.getString("account_id"),
        rs.getString("sku"),
        rs.getString("product_id"),
        rs.getString("product_name"),
        rs.getInt("delivered_qty"),
        rs.getTimestamp("delivered_at").toInstant(),
        rs.getString("delivery_id"));
  }

  private static ReviewRow reviewRow(ResultSet rs) throws SQLException {
    return new ReviewRow(
        rs.getString("review_id"),
        rs.getString("order_line_id"),
        rs.getString("order_id"),
        rs.getString("account_id"),
        rs.getString("product_id"),
        rs.getString("sku"),
        rs.getString("author_name"),
        rs.getInt("rating"),
        rs.getString("body"),
        rs.getString("state"),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant(),
        instant(rs, "updated_at"),
        instant(rs, "deleted_at"));
  }

  private static PhotoRow photoRow(ResultSet rs) throws SQLException {
    return new PhotoRow(
        rs.getString("photo_id"),
        rs.getString("account_id"),
        rs.getString("object_key"),
        rs.getString("content_type"),
        rs.getLong("size_bytes"),
        rs.getString("sha256"),
        rs.getTimestamp("uploaded_at").toInstant(),
        rs.getString("review_id"));
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.from(i);
  }
}
