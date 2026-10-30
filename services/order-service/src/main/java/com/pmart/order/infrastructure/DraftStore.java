package com.pmart.order.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 주문 초안 저장(JDBC). 내용은 snapshot(jsonb) 하나로 고정한다. */
@Repository
public class DraftStore {

  public record DraftRow(
      String draftId,
      String accountId,
      String purchaseIntentId,
      String status,
      String mfcId,
      String snapshot,
      int version,
      Instant createdAt,
      Instant quoteExpiresAt) {}

  private final JdbcTemplate jdbc;

  public DraftStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(DraftRow d) {
    jdbc.update(
        "INSERT INTO order_draft (draft_id, account_id, purchase_intent_id, status, mfc_id, snapshot,"
            + " version, created_at, quote_expires_at) VALUES (?,?,?,?,?,?::jsonb,?,?,?)",
        d.draftId(),
        d.accountId(),
        d.purchaseIntentId(),
        d.status(),
        d.mfcId(),
        d.snapshot(),
        d.version(),
        Timestamp.from(d.createdAt()),
        Timestamp.from(d.quoteExpiresAt()));
  }

  public Optional<DraftRow> draft(String draftId) {
    return jdbc
        .query(
            "SELECT *, snapshot::text AS snap FROM order_draft WHERE draft_id = ?",
            (rs, i) -> row(rs),
            draftId)
        .stream()
        .findFirst();
  }

  /** 같은 구매 의도의 가장 최근 초안. */
  public Optional<DraftRow> latestByIntent(String accountId, String purchaseIntentId) {
    return jdbc
        .query(
            "SELECT *, snapshot::text AS snap FROM order_draft WHERE account_id = ? AND purchase_intent_id = ?"
                + " ORDER BY created_at DESC LIMIT 1",
            (rs, i) -> row(rs),
            accountId,
            purchaseIntentId)
        .stream()
        .findFirst();
  }

  private static DraftRow row(ResultSet rs) throws SQLException {
    return new DraftRow(
        rs.getString("draft_id"),
        rs.getString("account_id"),
        rs.getString("purchase_intent_id"),
        rs.getString("status"),
        rs.getString("mfc_id"),
        rs.getString("snap"),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("quote_expires_at").toInstant());
  }
}
