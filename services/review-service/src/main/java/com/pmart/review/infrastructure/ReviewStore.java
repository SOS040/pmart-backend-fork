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
      @Nullable String deliveryId,
      @Nullable Instant orderedAt,
      @Nullable String mfcId) {}

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
      @Nullable Instant deletedAt,
      int helpfulCount) {

    /** 도움돼요 수 없이(새 리뷰·수정 사건 — 0.6.0 전과 같은 자리). */
    public ReviewRow(
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
        @Nullable Instant deletedAt) {
      this(
          reviewId,
          orderLineId,
          orderId,
          accountId,
          productId,
          sku,
          authorName,
          rating,
          body,
          state,
          version,
          createdAt,
          updatedAt,
          deletedAt,
          0);
    }
  }

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

  /**
   * 본인 자격 개수(0.5.0, 2026-11-20 BR-67) — total 은 요청 조건(orderId·state)에 맞는 전체, 나머지는 탭 머리 개수(state
   * 무시).
   */
  public record EligibilityCounts(int total, int writable, int written) {}

  /** 주문 하나의 리뷰 작성 여부(0.3.0, 2026-11-18 — 리뷰 요청 알림). */
  public record OrderStatus(int eligibleLines, int reviewedLines, int openLines) {}

  private final JdbcTemplate jdbc;

  public ReviewStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------------------------------------------------------------- 자격

  /**
   * 수령 사실 반영: 처음이면 행을 만들고, 이미 있으면 수량을 더하고 수령 시각은 이른 것을 둔다(분할 수령 — 첫 수령부터 14일). 같은 사건의 재전달은 inbox 가
   * 막는다. 주문 확정 시각·센터(V4)는 비어 있던 행만 채운다.
   */
  public void upsertEligibility(EligibilityRow r, Instant now) {
    jdbc.update(
        "INSERT INTO review_eligibility (order_line_id, order_id, account_id, sku, product_id,"
            + " product_name, delivered_qty, delivered_at, delivery_id, created_at, ordered_at, mfc_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (order_line_id) DO UPDATE SET"
            + " delivered_qty = review_eligibility.delivered_qty + EXCLUDED.delivered_qty,"
            + " delivered_at = LEAST(review_eligibility.delivered_at, EXCLUDED.delivered_at),"
            + " ordered_at = COALESCE(review_eligibility.ordered_at, EXCLUDED.ordered_at),"
            + " mfc_id = COALESCE(review_eligibility.mfc_id, EXCLUDED.mfc_id)",
        r.orderLineId(),
        r.orderId(),
        r.accountId(),
        r.sku(),
        r.productId(),
        r.productName(),
        r.deliveredQty(),
        ts(r.deliveredAt()),
        r.deliveryId(),
        ts(now),
        r.orderedAt() == null ? null : ts(r.orderedAt()),
        r.mfcId());
  }

  /** V4 전에 만든 행의 주문 확정 시각·센터를 채운다(비어 있는 것만 — 이미 있는 값은 바꾸지 않는다). */
  public void fillOrderFacts(String orderId, @Nullable Instant orderedAt, @Nullable String mfcId) {
    jdbc.update(
        "UPDATE review_eligibility SET ordered_at = COALESCE(ordered_at, ?),"
            + " mfc_id = COALESCE(mfc_id, ?) WHERE order_id = ?",
        orderedAt == null ? null : ts(orderedAt),
        mfcId,
        orderId);
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

  /** 계약 상태를 시각으로 계산하는 식(자리표시자 둘 다 now). LOCKED·EXPIRED 는 저장하지 않는다. */
  private static final String STATE_EXPR =
      "CASE WHEN r.review_id IS NULL THEN"
          + " CASE WHEN e.delivered_at + interval '14 days' >= ? THEN 'REV_ELIGIBLE' ELSE 'EXPIRED' END"
          + " WHEN r.state = 'DELETED' THEN 'DELETED'"
          + " WHEN r.created_at + interval '3 days' > ? THEN 'PUBLISHED' ELSE 'LOCKED' END";

  /** 본인 자격 목록(최근 수령 순). state 는 계약 상태(시각으로 계산) — SQL 에서 같은 식으로 걸러 페이지가 어긋나지 않게 한다. */
  public List<EligibilityView> eligibilities(
      String accountId,
      @Nullable String orderId,
      @Nullable String state,
      Instant now,
      int offset,
      int limit) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder("SELECT * FROM " + eligibilityFrom(accountId, orderId, now, args) + " x");
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

  /**
   * 자격 개수(0.5.0 BR-67): total = 목록과 같은 조건(orderId·state)의 전체 수, writable = 지금 쓸 수 있는
   * 것(REV_ELIGIBLE), written = 쓴 리뷰 중 삭제 안 된 것(PUBLISHED·LOCKED). writable·written 은 state 조건을 보지
   * 않는다(탭 머리 두 개를 한 번에).
   */
  public EligibilityCounts eligibilityCounts(
      String accountId, @Nullable String orderId, @Nullable String state, Instant now) {
    List<Object> args = new ArrayList<>();
    String countState = state == null ? "count(*)" : "count(*) FILTER (WHERE computed_state = ?)";
    if (state != null) {
      args.add(state);
    }
    String sql =
        "SELECT "
            + countState
            + " AS total,"
            + " count(*) FILTER (WHERE computed_state = 'REV_ELIGIBLE') AS writable,"
            + " count(*) FILTER (WHERE computed_state IN ('PUBLISHED','LOCKED')) AS written FROM "
            + eligibilityFrom(accountId, orderId, now, args)
            + " x";
    return jdbc.queryForObject(
        sql,
        (rs, i) ->
            new EligibilityCounts(rs.getInt("total"), rs.getInt("writable"), rs.getInt("written")),
        args.toArray());
  }

  /** 본인 자격 + 리뷰 + 계산 상태(computed_state) 부분 질의. 자리표시자 값은 args 뒤에 붙인다. */
  private static String eligibilityFrom(
      String accountId, @Nullable String orderId, Instant now, List<Object> args) {
    StringBuilder sql =
        new StringBuilder(
            "(SELECT e.*, r.review_id AS r_id, r.state AS r_state,"
                + " r.created_at AS r_created_at, "
                + STATE_EXPR
                + " AS computed_state FROM review_eligibility e"
                + " LEFT JOIN review r ON r.order_line_id = e.order_line_id"
                + " WHERE e.account_id = ?");
    args.add(ts(now));
    args.add(ts(now));
    args.add(accountId);
    if (orderId != null) {
      sql.append(" AND e.order_id = ?");
      args.add(orderId);
    }
    return sql.append(")").toString();
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
      String productId,
      String sort,
      boolean withPhotoOnly,
      @Nullable String sku,
      int offset,
      int limit) {
    String order =
        switch (sort) {
          case "RATING_DESC" -> "rating DESC, created_at DESC, review_id";
          case "RATING_ASC" -> "rating ASC, created_at DESC, review_id";
          // BR-76(0.6.0) — 도움돼요 많은 순, 같으면 최근 등록순
          case "RECOMMENDED" -> "helpful_count DESC, created_at DESC, review_id";
          default -> "created_at DESC, review_id";
        };
    List<Object> args = new ArrayList<>();
    args.add(productId);
    if (sku != null) {
      args.add(sku);
    }
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        "SELECT * FROM review r WHERE product_id = ? AND state = 'PUBLISHED'"
            + filters(withPhotoOnly, sku)
            + " ORDER BY "
            + order
            + " LIMIT ? OFFSET ?",
        (rs, i) -> reviewRow(rs),
        args.toArray());
  }

  /** 목록과 같은 거르기의 공개 리뷰 수(0.6.0 — summary.reviewCount). */
  public int filteredCount(String productId, boolean withPhotoOnly, @Nullable String sku) {
    Integer n =
        sku == null
            ? jdbc.queryForObject(
                "SELECT count(*) FROM review r WHERE product_id = ? AND state = 'PUBLISHED'"
                    + filters(withPhotoOnly, null),
                Integer.class,
                productId)
            : jdbc.queryForObject(
                "SELECT count(*) FROM review r WHERE product_id = ? AND state = 'PUBLISHED'"
                    + filters(withPhotoOnly, sku),
                Integer.class,
                productId,
                sku);
    return n == null ? 0 : n;
  }

  private static String filters(boolean withPhotoOnly, @Nullable String sku) {
    return (withPhotoOnly
            ? " AND EXISTS (SELECT 1 FROM review_photo p WHERE p.review_id = r.review_id)"
            : "")
        + (sku == null ? "" : " AND r.sku = ?");
  }

  // ---------------------------------------------------------------- 도움돼요(BR-76)

  /** 누름. 새로 눌렀으면 수 +1. 바뀐 수. */
  public int markHelpful(String reviewId, String accountId, Instant at) {
    int added =
        jdbc.update(
            "INSERT INTO review_helpful (review_id, account_id, created_at) VALUES (?,?,?)"
                + " ON CONFLICT DO NOTHING",
            reviewId,
            accountId,
            ts(at));
    if (added == 1) {
      jdbc.update(
          "UPDATE review SET helpful_count = helpful_count + 1 WHERE review_id = ?", reviewId);
    }
    return helpfulCount(reviewId);
  }

  /** 취소. 지웠으면 수 −1. 바뀐 수. */
  public int unmarkHelpful(String reviewId, String accountId) {
    int removed =
        jdbc.update(
            "DELETE FROM review_helpful WHERE review_id = ? AND account_id = ?",
            reviewId,
            accountId);
    if (removed == 1) {
      jdbc.update(
          "UPDATE review SET helpful_count = GREATEST(helpful_count - 1, 0) WHERE review_id = ?",
          reviewId);
    }
    return helpfulCount(reviewId);
  }

  private int helpfulCount(String reviewId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT helpful_count FROM review WHERE review_id = ?", Integer.class, reviewId);
    return n == null ? 0 : n;
  }

  /** 이 회원이 누른 리뷰(쪽 안). */
  public java.util.Set<String> helpedBy(String accountId, java.util.Collection<String> reviewIds) {
    if (reviewIds.isEmpty()) {
      return java.util.Set.of();
    }
    return new java.util.HashSet<>(
        jdbc.queryForList(
            "SELECT review_id FROM review_helpful WHERE account_id = ? AND review_id = ANY (?::varchar[])",
            String.class,
            accountId,
            "{" + String.join(",", reviewIds) + "}"));
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

  /**
   * 이 계정의 그 주문 품목: 자격 수·리뷰 쓴 수(삭제 포함 — 재작성 불가)·아직 쓸 수 있는 수(리뷰 없음, 수령이 {@code writableSince} 이후 — 작성
   * 기한 경계 포함).
   */
  public OrderStatus orderStatus(String orderId, String accountId, Instant writableSince) {
    return jdbc.queryForObject(
        "SELECT count(*) AS eligible, count(r.review_id) AS reviewed,"
            + " count(*) FILTER (WHERE r.review_id IS NULL AND e.delivered_at >= ?) AS open"
            + " FROM review_eligibility e LEFT JOIN review r ON r.order_line_id = e.order_line_id"
            + " WHERE e.order_id = ? AND e.account_id = ?",
        (rs, i) -> new OrderStatus(rs.getInt("eligible"), rs.getInt("reviewed"), rs.getInt("open")),
        ts(writableSince),
        orderId,
        accountId);
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

  /**
   * 축소본을 아직 만들지 않은 사진(JPEG·PNG, 오래된 것부터 limit 장) — ReviewPhotoPreparer 가 차례로 만든다(2026-11-20, V3).
   * 리뷰에 붙지 않은 사진도 — 작성 직후 보이게.
   */
  public List<PhotoRow> photosWithoutVariants(int limit) {
    return jdbc.query(
        "SELECT * FROM review_photo WHERE variants_at IS NULL"
            + " AND content_type IN ('image/jpeg','image/png') ORDER BY uploaded_at LIMIT ?",
        (rs, i) -> photoRow(rs),
        limit);
  }

  public void markVariants(String photoId, Instant at) {
    jdbc.update(
        "UPDATE review_photo SET variants_at = ? WHERE photo_id = ?", Timestamp.from(at), photoId);
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
        rs.getString("delivery_id"),
        instant(rs, "ordered_at"),
        rs.getString("mfc_id"));
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
        instant(rs, "deleted_at"),
        rs.getInt("helpful_count"));
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
