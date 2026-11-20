package com.pmart.order.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
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
      Instant quoteExpiresAt,
      @Nullable String orderId,
      @Nullable String sagaId) {}

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

  /** 결제 진입(주문 ID·사가)은 있는데 확정 주문이 없는 초안 — 탈퇴 의무 확인(ADR-0077)이 사가 상태를 묻는다. */
  public record Checkout(String orderId, String sagaId) {}

  public java.util.List<Checkout> checkoutsWithoutOrder(String accountId) {
    return jdbc.query(
        "SELECT d.order_id, d.saga_id FROM order_draft d WHERE d.account_id = ?"
            + " AND d.order_id IS NOT NULL AND d.saga_id IS NOT NULL"
            + " AND NOT EXISTS (SELECT 1 FROM customer_order o WHERE o.order_id = d.order_id)"
            + " ORDER BY d.created_at, d.draft_id",
        (rs, i) -> new Checkout(rs.getString("order_id"), rs.getString("saga_id")),
        accountId);
  }

  /** 결제 진입 시작 — 주문 ID·사가 참조를 남기고 버전을 올린다. */
  public void checkoutStarted(
      String draftId, String orderId, String sagaId, String requestId, Instant at) {
    jdbc.update(
        "UPDATE order_draft SET order_id = ?, saga_id = ?, checkout_request_id = ?, checkout_at = ?,"
            + " status = 'RESOURCE_PENDING', version = version + 1 WHERE draft_id = ?",
        orderId,
        sagaId,
        requestId,
        Timestamp.from(at),
        draftId);
  }

  /** 행 잠금(같은 초안의 결제 진입을 한 번에 하나만). */
  public Optional<DraftRow> lock(String draftId) {
    return jdbc
        .query(
            "SELECT *, snapshot::text AS snap FROM order_draft WHERE draft_id = ? FOR UPDATE",
            (rs, i) -> row(rs),
            draftId)
        .stream()
        .findFirst();
  }

  /** 주문 ID 로 초안 행 잠금(주문 확정 — 같은 주문의 확정을 한 번에 하나만). */
  public Optional<DraftRow> lockByOrderId(String orderId) {
    return jdbc
        .query(
            "SELECT *, snapshot::text AS snap FROM order_draft WHERE order_id = ? FOR UPDATE",
            (rs, i) -> row(rs),
            orderId)
        .stream()
        .findFirst();
  }

  public Optional<DraftRow> byOrderId(String orderId) {
    return jdbc
        .query(
            "SELECT *, snapshot::text AS snap FROM order_draft WHERE order_id = ?",
            (rs, i) -> row(rs),
            orderId)
        .stream()
        .findFirst();
  }

  /** 주문 확정 — 초안을 CONFIRMED 로 닫는다(다시 결제 진입하지 않는다). */
  public void confirmed(String draftId) {
    jdbc.update(
        "UPDATE order_draft SET status = 'CONFIRMED', version = version + 1 WHERE draft_id = ?",
        draftId);
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
        rs.getTimestamp("quote_expires_at").toInstant(),
        rs.getString("order_id"),
        rs.getString("saga_id"));
  }
}
