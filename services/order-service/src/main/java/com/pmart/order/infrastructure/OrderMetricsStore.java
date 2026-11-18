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
}
