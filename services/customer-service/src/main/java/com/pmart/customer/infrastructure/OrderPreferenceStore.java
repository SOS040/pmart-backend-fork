package com.pmart.customer.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 주문 기본 설정(V7, 2026-11-18 — 계약 0.4.0). 계정마다 한 행, 없으면 저장한 적 없음. */
@Repository
public class OrderPreferenceStore {

  public record Row(
      String accountId, String outOfStockPreference, int version, Instant updatedAt) {}

  private final JdbcTemplate jdbc;

  public OrderPreferenceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Row> find(String accountId) {
    return jdbc
        .query(
            "SELECT account_id, out_of_stock_preference, version, updated_at"
                + " FROM customer_order_preference WHERE account_id = ?",
            (rs, i) ->
                new Row(
                    rs.getString("account_id"),
                    rs.getString("out_of_stock_preference"),
                    rs.getInt("version"),
                    rs.getTimestamp("updated_at").toInstant()),
            accountId)
        .stream()
        .findFirst();
  }

  /** 첫 저장(version 1). 같은 계정 행이 그 사이 생겼으면 0 — 호출자가 버전 충돌로 본다. */
  public int insert(String accountId, String outOfStockPreference, Instant now) {
    return jdbc.update(
        "INSERT INTO customer_order_preference (account_id, out_of_stock_preference, version,"
            + " updated_at) VALUES (?,?,1,?) ON CONFLICT (account_id) DO NOTHING",
        accountId,
        outOfStockPreference,
        Timestamp.from(now));
  }

  /** 값 바꾸기(버전 확인, +1). 바뀐 행 수(0 = 버전 충돌). */
  public int update(
      String accountId, int expectedVersion, String outOfStockPreference, Instant now) {
    return jdbc.update(
        "UPDATE customer_order_preference SET out_of_stock_preference = ?, version = version + 1,"
            + " updated_at = ? WHERE account_id = ? AND version = ?",
        outOfStockPreference,
        Timestamp.from(now),
        accountId,
        expectedVersion);
  }
}
