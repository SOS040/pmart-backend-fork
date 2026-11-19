package com.pmart.storefront.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 판매 원장·순위 신호(V7, 2026-11-19 ADR-0072). */
@Repository
public class RankingStore {

  public record Signal(
      String productId,
      int ratingCount,
      @Nullable Double ratingAverage,
      @Nullable Double ratingScore,
      int salesWindow,
      int windowDays,
      int version) {}

  private final JdbcTemplate jdbc;

  public RankingStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 주문 확정 — 줄을 만든다. 이미 있으면(변경 사건이 먼저 옴) 확정 시각만 채운다. 수량은 더 큰 주문 버전만. */
  public void confirmedLine(
      String orderLineId,
      String orderId,
      String sku,
      int qty,
      int version,
      Instant confirmedAt,
      Instant now) {
    jdbc.update(
        "INSERT INTO sku_sales_line (order_line_id, order_id, sku, confirmed_at, qty, version, updated_at)"
            + " VALUES (?,?,?,?,?,?,?) ON CONFLICT (order_line_id) DO UPDATE SET"
            + " confirmed_at = COALESCE(sku_sales_line.confirmed_at, EXCLUDED.confirmed_at),"
            + " qty = CASE WHEN EXCLUDED.version > sku_sales_line.version THEN EXCLUDED.qty ELSE sku_sales_line.qty END,"
            + " version = GREATEST(sku_sales_line.version, EXCLUDED.version), updated_at = EXCLUDED.updated_at",
        orderLineId,
        orderId,
        sku,
        Timestamp.from(confirmedAt),
        qty,
        version,
        Timestamp.from(now));
  }

  /** 줄 수량 변경(누적 값) — 더 큰 주문 버전만. 줄이 아직 없으면 확정 시각 없이 만든다(확정 사건이 오면 채움). */
  /**
   * 줄 수량 변경(주문 버전이 큰 것만). 순 수량 = 원수량 − 출고 전 취소 − 반품. returnedQty 는 반품 사건에만 실려 오므로(order) 없으면 마지막으로
   * 받은 반품 수를 쓴다(2026-11-20 리뷰 지적 3).
   */
  public void changedLine(
      String orderLineId,
      String orderId,
      String sku,
      int originalQty,
      int cancelledBeforeShipQty,
      @Nullable Integer returnedQty,
      int version,
      Instant now) {
    jdbc.update(
        "INSERT INTO sku_sales_line (order_line_id, order_id, sku, confirmed_at, qty, version, updated_at, returned_qty)"
            + " VALUES (?,?,?,NULL, GREATEST(0, ? - ? - COALESCE(?::int, 0)), ?, ?, COALESCE(?::int, 0))"
            + " ON CONFLICT (order_line_id) DO UPDATE SET"
            + " returned_qty = COALESCE(?::int, sku_sales_line.returned_qty),"
            + " qty = GREATEST(0, ? - ? - COALESCE(?::int, sku_sales_line.returned_qty)),"
            + " version = EXCLUDED.version, updated_at = EXCLUDED.updated_at"
            + " WHERE sku_sales_line.version < EXCLUDED.version",
        orderLineId,
        orderId,
        sku,
        originalQty,
        cancelledBeforeShipQty,
        returnedQty,
        version,
        Timestamp.from(now),
        returnedQty,
        returnedQty,
        originalQty,
        cancelledBeforeShipQty,
        returnedQty);
  }

  public long sales(String sku, Instant since) {
    Long n =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(qty), 0) FROM sku_sales_line WHERE sku = ? AND confirmed_at >= ?",
            Long.class,
            sku,
            Timestamp.from(since));
    return n == null ? 0 : n;
  }

  /** 공개 리뷰 수·별점 합(V6 review_rating — 진열 평점과 같은 사본). */
  public long[] ratings(String productId) {
    return jdbc.queryForObject(
        "SELECT count(*), COALESCE(SUM(rating), 0) FROM review_rating WHERE product_id = ? AND NOT deleted",
        (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)},
        productId);
  }

  /** 그 SKU 를 대표로 둔 상품(지금은 상품 = 대표 SKU 하나). */
  public List<String> productsBySku(String sku) {
    return jdbc.queryForList(
        "SELECT product_id FROM display_product WHERE representative_sku = ?", String.class, sku);
  }

  public Optional<String> representativeSku(String productId) {
    return jdbc
        .queryForList(
            "SELECT representative_sku FROM display_product WHERE product_id = ? AND representative_sku IS NOT NULL",
            String.class,
            productId)
        .stream()
        .findFirst();
  }

  /** 신호를 다시 셀 상품 — 진열 상품 + 리뷰만 있는 상품. */
  public List<String> allProducts() {
    return jdbc.queryForList(
        "SELECT product_id FROM display_product UNION SELECT DISTINCT product_id FROM review_rating",
        String.class);
  }

  public Optional<Signal> signal(String productId) {
    return jdbc
        .query(
            "SELECT * FROM product_ranking_signal WHERE product_id = ?",
            (rs, i) ->
                new Signal(
                    rs.getString("product_id"),
                    rs.getInt("rating_count"),
                    toDouble(rs.getBigDecimal("rating_average")),
                    toDouble(rs.getBigDecimal("rating_score")),
                    rs.getInt("sales_window"),
                    rs.getInt("window_days"),
                    rs.getInt("version")),
            productId)
        .stream()
        .findFirst();
  }

  public void save(Signal s, Instant at) {
    jdbc.update(
        "INSERT INTO product_ranking_signal (product_id, rating_count, rating_average, rating_score, sales_window,"
            + " window_days, version, published_at) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (product_id) DO UPDATE SET"
            + " rating_count = EXCLUDED.rating_count, rating_average = EXCLUDED.rating_average,"
            + " rating_score = EXCLUDED.rating_score, sales_window = EXCLUDED.sales_window,"
            + " window_days = EXCLUDED.window_days, version = EXCLUDED.version, published_at = EXCLUDED.published_at",
        s.productId(),
        s.ratingCount(),
        s.ratingAverage(),
        s.ratingScore(),
        s.salesWindow(),
        s.windowDays(),
        s.version(),
        Timestamp.from(at));
  }

  private static @Nullable Double toDouble(@Nullable BigDecimal v) {
    return v == null ? null : v.doubleValue();
  }
}
