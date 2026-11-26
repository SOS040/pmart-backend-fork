package com.pmart.order.infrastructure;

import static com.pmart.order.domain.PurchaseCountRules.LINE_PURCHASED;
import static com.pmart.order.domain.PurchaseCountRules.LINE_REMAINING_AMOUNT;
import static com.pmart.order.domain.PurchaseCountRules.LINE_REMAINING_QTY;
import static com.pmart.order.domain.PurchaseCountRules.ORDER_PURCHASED;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 구매 기록 집계 SQL(2026-11-26 — 자주 산 상품 ①·고객 구매 지표 O-13). "산 것" 조건은 {@link
 * com.pmart.order.domain.PurchaseCountRules} 한 곳. 읽기 전용.
 */
@Repository
public class PurchaseHistoryStore {

  /** SKU 하나 — 이름·옵션·상품 ID 는 가장 최근 주문의 사본(없으면 다른 주문의 값). */
  public record FrequentRow(
      String sku,
      @Nullable String productId,
      @Nullable String productName,
      @Nullable String optionLabel,
      int orderCount,
      int totalQuantity,
      Instant lastPurchasedAt) {}

  /** 첫 구매 기준 고객 지표 분자·분모. */
  public record CustomerCounts(
      int customerCount,
      int repeatCustomerCount,
      int oneTimeCustomerCount,
      int orderCount,
      long purchaseAmount) {}

  /** O-12 고객 한 명 요약(산 것이 있는 고객만 — 없는 고객은 부른 쪽이 0 으로). */
  public record CustomerSummary(
      String customerId,
      int orderCount,
      long totalAmount,
      long recentAmount,
      Instant firstOrderAt,
      Instant lastOrderAt) {}

  /** O-12 고객 찾기 한 줄. */
  public record LastOrder(String customerId, Instant lastOrderAt) {}

  private final JdbcTemplate jdbc;

  public PurchaseHistoryStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 고객 한 명의 확정 시각 {@code since} 이후 산 SKU — 서로 다른 주문 수 {@code minOrders} 이상, 상위 {@code limit}. */
  public List<FrequentRow> frequentItems(
      String accountId, Instant since, int minOrders, int limit) {
    String sql =
        "WITH bought AS (SELECT l.sku, l.product_id, l.product_name, l.option_label, o.order_id,"
            + " o.confirmed_at, "
            + LINE_REMAINING_QTY
            + " AS qty,"
            + " row_number() OVER (PARTITION BY l.sku"
            + " ORDER BY o.confirmed_at DESC, o.order_id DESC, l.line_no) AS rn"
            + " FROM customer_order o JOIN customer_order_line l ON l.order_id = o.order_id"
            + " WHERE o.account_id = ? AND o.confirmed_at >= ? AND o.hidden_at IS NULL AND "
            + ORDER_PURCHASED
            + " AND "
            + LINE_PURCHASED
            + ")"
            + " SELECT sku, count(DISTINCT order_id) AS n, sum(qty) AS total,"
            + " max(confirmed_at) AS last_at,"
            + " coalesce(max(CASE WHEN rn = 1 THEN product_id END), max(product_id)) AS product_id,"
            + " coalesce(max(CASE WHEN rn = 1 THEN product_name END), max(product_name))"
            + " AS product_name,"
            + " coalesce(max(CASE WHEN rn = 1 THEN option_label END), max(option_label))"
            + " AS option_label"
            + " FROM bought GROUP BY sku HAVING count(DISTINCT order_id) >= ?"
            + " ORDER BY n DESC, last_at DESC, sku ASC LIMIT ?";
    return jdbc.query(
        sql,
        (rs, i) ->
            new FrequentRow(
                rs.getString("sku"),
                rs.getString("product_id"),
                rs.getString("product_name"),
                rs.getString("option_label"),
                rs.getInt("n"),
                rs.getInt("total"),
                rs.getTimestamp("last_at").toInstant()),
        accountId,
        Timestamp.from(since),
        minOrders,
        limit);
  }

  /**
   * 첫 산 주문의 확정 시각이 [from, toExclusive) 인 고객 — 그 고객의 산 주문을 toExclusive 전까지 센다. {@code mfcId} 를 주면 그
   * 센터 주문만으로 첫 구매·횟수·금액.
   */
  public CustomerCounts customerCounts(Instant from, Instant toExclusive, @Nullable String mfcId) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            "WITH bought AS (SELECT o.order_id, o.account_id, o.confirmed_at, sum("
                + LINE_REMAINING_AMOUNT
                + ") AS amt"
                + " FROM customer_order o JOIN customer_order_line l ON l.order_id = o.order_id"
                + " WHERE o.confirmed_at < ? AND "
                + ORDER_PURCHASED
                + " AND "
                + LINE_PURCHASED);
    args.add(Timestamp.from(toExclusive));
    if (mfcId != null) {
      sql.append(" AND o.mfc_id = ?");
      args.add(mfcId);
    }
    sql.append(
        " GROUP BY o.order_id, o.account_id, o.confirmed_at),"
            + " per AS (SELECT account_id, min(confirmed_at) AS first_at, count(*) AS n,"
            + " sum(amt) AS amt FROM bought GROUP BY account_id)"
            + " SELECT count(*) AS customers,"
            + " count(*) FILTER (WHERE n >= 2) AS repeaters,"
            + " count(*) FILTER (WHERE n = 1) AS one_timers,"
            + " coalesce(sum(n), 0) AS orders, coalesce(sum(amt), 0) AS amount"
            + " FROM per WHERE first_at >= ?");
    args.add(Timestamp.from(from));
    return jdbc.queryForObject(
        sql.toString(),
        (rs, i) ->
            new CustomerCounts(
                rs.getInt("customers"),
                rs.getInt("repeaters"),
                rs.getInt("one_timers"),
                rs.getInt("orders"),
                rs.getLong("amount")),
        args.toArray());
  }

  /** O-12: 고객들의 산 주문 수·누적 금액·{@code recentSince} 이후 금액·첫·마지막 구매. */
  public List<CustomerSummary> customerSummaries(List<String> customerIds, Instant recentSince) {
    String sql =
        "WITH bought AS (SELECT o.order_id, o.account_id, o.confirmed_at, sum("
            + LINE_REMAINING_AMOUNT
            + ") AS amt"
            + " FROM customer_order o JOIN customer_order_line l ON l.order_id = o.order_id"
            + " WHERE o.account_id = ANY (?) AND "
            + ORDER_PURCHASED
            + " AND "
            + LINE_PURCHASED
            + " GROUP BY o.order_id, o.account_id, o.confirmed_at)"
            + " SELECT account_id, count(*) AS n, coalesce(sum(amt), 0) AS total,"
            + " coalesce(sum(amt) FILTER (WHERE confirmed_at >= ?), 0) AS recent,"
            + " min(confirmed_at) AS first_at, max(confirmed_at) AS last_at"
            + " FROM bought GROUP BY account_id";
    return jdbc.query(
        sql,
        (rs, i) ->
            new CustomerSummary(
                rs.getString("account_id"),
                rs.getInt("n"),
                rs.getLong("total"),
                rs.getLong("recent"),
                rs.getTimestamp("first_at").toInstant(),
                rs.getTimestamp("last_at").toInstant()),
        customerIds.toArray(String[]::new),
        Timestamp.from(recentSince));
  }

  /**
   * O-12: 마지막 산 주문의 확정 시각이 [from, toExclusive) 인 고객(둘 다 null 가능), 마지막 구매 최근 순 → 고객 ID. {@code
   * after} 는 커서(그 줄 다음부터).
   */
  public List<LastOrder> customersByLastOrder(
      @Nullable Instant from,
      @Nullable Instant toExclusive,
      @Nullable Instant afterLastOrderAt,
      @Nullable String afterCustomerId,
      int limit) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            "WITH last AS (SELECT o.account_id, max(o.confirmed_at) AS last_at FROM customer_order o"
                + " WHERE "
                + ORDER_PURCHASED
                + " AND EXISTS (SELECT 1 FROM customer_order_line l WHERE l.order_id = o.order_id AND "
                + LINE_PURCHASED
                + ") GROUP BY o.account_id)"
                + " SELECT account_id, last_at FROM last WHERE true");
    if (from != null) {
      sql.append(" AND last_at >= ?");
      args.add(Timestamp.from(from));
    }
    if (toExclusive != null) {
      sql.append(" AND last_at < ?");
      args.add(Timestamp.from(toExclusive));
    }
    if (afterLastOrderAt != null && afterCustomerId != null) {
      sql.append(" AND (last_at < ? OR (last_at = ? AND account_id > ?))");
      args.add(Timestamp.from(afterLastOrderAt));
      args.add(Timestamp.from(afterLastOrderAt));
      args.add(afterCustomerId);
    }
    sql.append(" ORDER BY last_at DESC, account_id ASC LIMIT ?");
    args.add(limit);
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new LastOrder(rs.getString("account_id"), rs.getTimestamp("last_at").toInstant()),
        args.toArray());
  }
}
