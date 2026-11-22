package com.pmart.order.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 직원용 기간 집계 SQL(2026-11-18, O-8). 확정 시각 범위로 {@code customer_order} 를 거른다 — 색인
 * ix_customer_order_confirmed(모든 센터)·ix_customer_order_mfc(센터 하나). 읽기 전용, 개인정보 열(배송지·고객) 없음.
 */
@Repository
public class OrderMetricsStore {

  /** 상태 하나의 주문 수·결제 승인 금액 합. */
  public record StatusRow(String status, int count, long approvedAmount) {}

  /** SKU 하나의 남은 수량·금액. */
  public record ProductRow(String sku, @Nullable String productId, int qty, long amount) {}

  private final JdbcTemplate jdbc;

  public OrderMetricsStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<StatusRow> statusCounts(Instant from, Instant toExclusive, @Nullable String mfcId) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            "SELECT status, count(*) AS n, coalesce(sum(approved_amount), 0) AS amount"
                + " FROM customer_order WHERE confirmed_at >= ? AND confirmed_at < ?");
    args.add(Timestamp.from(from));
    args.add(Timestamp.from(toExclusive));
    if (mfcId != null) {
      sql.append(" AND mfc_id = ?");
      args.add(mfcId);
    }
    sql.append(" GROUP BY status ORDER BY status");
    return jdbc.query(
        sql.toString(),
        (rs, n) -> new StatusRow(rs.getString("status"), rs.getInt("n"), rs.getLong("amount")),
        args.toArray());
  }

  /**
   * SKU별 남은 수량(주문 수량 − 출고 전 취소)과 금액(행마다 (줄 금액 − 할인 분담) × 남은 수량 ÷ 주문 수량, 정수 나눗셈 = 원 미만 버림), 금액 내림차순
   * 상위 {@code limit}. 남은 수량 0 인 SKU 는 뺀다.
   */
  public List<ProductRow> topProducts(
      Instant from, Instant toExclusive, @Nullable String mfcId, int limit) {
    return topProducts(from, toExclusive, mfcId, null, limit);
  }

  /** {@code productIds} 가 있으면 그 상품 줄만(0.20.0 카테고리 거르기 — 빈 집합이면 아무것도). */
  public List<ProductRow> topProducts(
      Instant from,
      Instant toExclusive,
      @Nullable String mfcId,
      java.util.@Nullable Collection<String> productIds,
      int limit) {
    if (productIds != null && productIds.isEmpty()) {
      return List.of();
    }
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            "SELECT l.sku, max(l.product_id) AS product_id,"
                + " sum(greatest(l.quantity - l.cancelled_before_ship_qty, 0)) AS qty,"
                + " sum(CASE WHEN l.quantity > 0"
                + " THEN (l.line_amount - l.discount_share)"
                + " * greatest(l.quantity - l.cancelled_before_ship_qty, 0) / l.quantity"
                + " ELSE 0 END) AS amount"
                + " FROM customer_order o JOIN customer_order_line l ON l.order_id = o.order_id"
                + " WHERE o.confirmed_at >= ? AND o.confirmed_at < ?");
    args.add(Timestamp.from(from));
    args.add(Timestamp.from(toExclusive));
    if (mfcId != null) {
      sql.append(" AND o.mfc_id = ?");
      args.add(mfcId);
    }
    if (productIds != null) {
      sql.append(" AND l.product_id = ANY (?)");
      args.add(productIds.toArray(String[]::new));
    }
    sql.append(
        " GROUP BY l.sku"
            + " HAVING sum(greatest(l.quantity - l.cancelled_before_ship_qty, 0)) > 0"
            + " ORDER BY amount DESC, qty DESC, l.sku ASC LIMIT ?");
    args.add(limit);
    return jdbc.query(
        sql.toString(),
        (rs, n) ->
            new ProductRow(
                rs.getString("sku"),
                rs.getString("product_id"),
                rs.getInt("qty"),
                rs.getLong("amount")),
        args.toArray());
  }

  /**
   * 추이 한 칸(0.20.0 O-11). HOUR·DAY 는 {@code start}(한국 시각 칸 시작), WEEKDAY_HOUR 는 weekday(1=월)·hour.
   * approvedAmount 는 거르기 없을 때만(있으면 null).
   */
  public record SeriesRow(
      @Nullable Instant start,
      int weekday,
      int hour,
      int orderCount,
      @Nullable Long approvedAmount,
      long qty,
      long lineAmount) {}

  /**
   * 확정 시각 범위의 주문을 칸으로 센다. {@code sku}·{@code productIds} 가 있으면 그 줄이 든 주문만, 금액은 그 줄 금액만. 줄 금액 = (줄
   * 금액 − 할인 분담) × 남은 수량 ÷ 주문 수량(top-products 와 같은 산식). 0건 칸은 없다.
   */
  public List<SeriesRow> series(
      Instant from,
      Instant toExclusive,
      @Nullable String mfcId,
      String bucket,
      @Nullable String sku,
      java.util.@Nullable Collection<String> productIds) {
    if (productIds != null && productIds.isEmpty()) {
      return List.of();
    }
    boolean filtered = sku != null || productIds != null;
    String local = "(o.confirmed_at AT TIME ZONE 'Asia/Seoul')";
    String key =
        switch (bucket) {
          case "HOUR" -> "date_trunc('hour', " + local + ")";
          case "DAY" -> "date_trunc('day', " + local + ")";
          default ->
              "extract(isodow FROM " + local + ")::int, extract(hour FROM " + local + ")::int";
        };
    List<Object> args = new ArrayList<>();
    StringBuilder lineWhere = new StringBuilder();
    if (sku != null) {
      lineWhere.append(" AND l.sku = ?");
    }
    if (productIds != null) {
      lineWhere.append(" AND l.product_id = ANY (?)");
    }
    StringBuilder sql = new StringBuilder();
    sql.append("WITH ol AS (SELECT l.order_id,")
        .append(" sum(greatest(l.quantity - l.cancelled_before_ship_qty, 0)) AS qty,")
        .append(" sum(CASE WHEN l.quantity > 0 THEN (l.line_amount - l.discount_share)")
        .append(
            " * greatest(l.quantity - l.cancelled_before_ship_qty, 0) / l.quantity ELSE 0 END) AS amt")
        .append(" FROM customer_order_line l JOIN customer_order o ON o.order_id = l.order_id")
        .append(" WHERE o.confirmed_at >= ? AND o.confirmed_at < ?");
    args.add(Timestamp.from(from));
    args.add(Timestamp.from(toExclusive));
    if (mfcId != null) {
      sql.append(" AND o.mfc_id = ?");
      args.add(mfcId);
    }
    sql.append(lineWhere);
    if (sku != null) {
      args.add(sku);
    }
    if (productIds != null) {
      args.add(productIds.toArray(String[]::new));
    }
    sql.append(" GROUP BY l.order_id)")
        .append(" SELECT ")
        .append(
            bucket.equals("WEEKDAY_HOUR")
                ? key.replace("::int,", "::int AS wd,") + " AS hr"
                : key + " AS b")
        .append(", count(*) AS n, coalesce(sum(o.approved_amount), 0) AS approved,")
        .append(" coalesce(sum(ol.qty), 0) AS qty, coalesce(sum(ol.amt), 0) AS amt")
        .append(" FROM customer_order o ")
        .append(filtered ? "JOIN" : "LEFT JOIN")
        .append(" ol ON ol.order_id = o.order_id")
        .append(" WHERE o.confirmed_at >= ? AND o.confirmed_at < ?");
    args.add(Timestamp.from(from));
    args.add(Timestamp.from(toExclusive));
    if (mfcId != null) {
      sql.append(" AND o.mfc_id = ?");
      args.add(mfcId);
    }
    sql.append(
        bucket.equals("WEEKDAY_HOUR")
            ? " GROUP BY wd, hr ORDER BY wd, hr"
            : " GROUP BY b ORDER BY b");
    return jdbc.query(
        sql.toString(),
        (rs, n) -> {
          Instant start = null;
          int wd = 0;
          int hr = 0;
          if (bucket.equals("WEEKDAY_HOUR")) {
            wd = rs.getInt("wd");
            hr = rs.getInt("hr");
          } else {
            start =
                rs.getTimestamp("b")
                    .toLocalDateTime()
                    .atZone(java.time.ZoneId.of("Asia/Seoul"))
                    .toInstant();
          }
          return new SeriesRow(
              start,
              wd,
              hr,
              rs.getInt("n"),
              filtered ? null : rs.getLong("approved"),
              rs.getLong("qty"),
              rs.getLong("amt"));
        },
        args.toArray());
  }
}
