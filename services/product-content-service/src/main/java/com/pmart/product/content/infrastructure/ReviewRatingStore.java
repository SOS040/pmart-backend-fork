package com.pmart.product.content.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 리뷰 별점 사본(V3, 2026-11-19). 리뷰 버전이 큰 사건만 반영한다. */
@Repository
public class ReviewRatingStore {

  /** 상품의 공개 리뷰 집계. counts 키 1~5. */
  public record Summary(long count, long sum, Map<Integer, Long> counts) {}

  private final JdbcTemplate jdbc;

  public ReviewRatingStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 반영했으면 1, 더 새 버전이 이미 있으면 0. */
  public int upsert(
      String reviewId, String productId, int rating, boolean deleted, int version, Instant at) {
    return jdbc.update(
        "INSERT INTO review_rating (review_id, product_id, rating, deleted, version, updated_at)"
            + " VALUES (?,?,?,?,?,?) ON CONFLICT (review_id) DO UPDATE SET"
            + " product_id = EXCLUDED.product_id, rating = EXCLUDED.rating, deleted = EXCLUDED.deleted,"
            + " version = EXCLUDED.version, updated_at = EXCLUDED.updated_at"
            + " WHERE review_rating.version < EXCLUDED.version",
        reviewId,
        productId,
        rating,
        deleted,
        version,
        Timestamp.from(at));
  }

  /** 여러 상품의 집계(카드·상세 rating, 2026-11-19 BR-63). 리뷰가 없는 상품도 개수 0 으로 들어 있다. */
  public Map<String, Summary> summaries(java.util.Collection<String> productIds) {
    Map<String, Map<Integer, Long>> counts = new LinkedHashMap<>();
    for (String id : productIds) {
      Map<Integer, Long> c = new LinkedHashMap<>();
      for (int i = 1; i <= 5; i++) {
        c.put(i, 0L);
      }
      counts.put(id, c);
    }
    if (!counts.isEmpty()) {
      jdbc.query(
          "SELECT product_id, rating, count(*) AS n FROM review_rating"
              + " WHERE product_id = ANY(?) AND NOT deleted GROUP BY product_id, rating",
          rs -> {
            counts.get(rs.getString("product_id")).put(rs.getInt("rating"), rs.getLong("n"));
          },
          (Object) counts.keySet().toArray(String[]::new));
    }
    Map<String, Summary> out = new LinkedHashMap<>();
    counts.forEach(
        (id, c) -> {
          long n = 0;
          long sum = 0;
          for (var e : c.entrySet()) {
            n += e.getValue();
            sum += e.getValue() * e.getKey();
          }
          out.put(id, new Summary(n, sum, c));
        });
    return out;
  }

  public Summary summary(String productId) {
    Map<Integer, Long> counts = new LinkedHashMap<>();
    for (int i = 1; i <= 5; i++) {
      counts.put(i, 0L);
    }
    long[] total = new long[2];
    jdbc.query(
        "SELECT rating, count(*) AS n FROM review_rating WHERE product_id = ? AND NOT deleted"
            + " GROUP BY rating",
        rs -> {
          int r = rs.getInt("rating");
          long n = rs.getLong("n");
          counts.put(r, n);
          total[0] += n;
          total[1] += n * r;
        },
        productId);
    return new Summary(total[0], total[1], counts);
  }
}
