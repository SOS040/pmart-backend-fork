package com.pmart.claim.infrastructure;

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

/** 클레임·클레임 행·환불 의도 원장(JDBC). */
@Repository
public class ClaimStore {

  public record ClaimRow(
      String claimId,
      String orderId,
      String accountId,
      String type,
      String state,
      String reason,
      @Nullable String reasonDetail,
      String channel,
      @Nullable String stopRequestId,
      int version,
      String paymentAttemptId,
      long approvedAmount,
      long itemAmount,
      long bagFee,
      long deliveryTip,
      @Nullable String rejectReason,
      Instant openedAt,
      @Nullable Instant decidedAt,
      @Nullable Instant closedAt,
      Instant updatedAt) {}

  public record LineRow(
      String claimLineId,
      String claimId,
      String orderLineId,
      String sku,
      int requestedQty,
      @Nullable Integer approvedQty,
      String decision,
      long lineAmount) {}

  public record RefundRow(
      String refundIntentId,
      String claimId,
      String orderId,
      String paymentAttemptId,
      String scope,
      String reason,
      long amount,
      long itemAmount,
      long bagFee,
      long deliveryTip,
      String state,
      String cancellationRequestId,
      @Nullable String pgResult,
      @Nullable String pgTransactionId,
      @Nullable String failureCode,
      int attempts,
      @Nullable Instant requestedAt,
      @Nullable Instant completedAt,
      Instant createdAt,
      Instant updatedAt) {}

  private final JdbcTemplate jdbc;

  public ClaimStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 클레임 ----------

  public void insert(ClaimRow c, List<LineRow> lines) {
    jdbc.update(
        "INSERT INTO claim (claim_id, order_id, account_id, type, state, reason, reason_detail,"
            + " channel, stop_request_id, version, payment_attempt_id, approved_amount, item_amount,"
            + " bag_fee, delivery_tip, opened_at, updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        c.claimId(),
        c.orderId(),
        c.accountId(),
        c.type(),
        c.state(),
        c.reason(),
        c.reasonDetail(),
        c.channel(),
        c.stopRequestId(),
        c.version(),
        c.paymentAttemptId(),
        c.approvedAmount(),
        c.itemAmount(),
        c.bagFee(),
        c.deliveryTip(),
        ts(c.openedAt()),
        ts(c.updatedAt()));
    for (LineRow l : lines) {
      jdbc.update(
          "INSERT INTO claim_line (claim_line_id, claim_id, order_line_id, sku, requested_qty,"
              + " approved_qty, decision, line_amount) VALUES (?,?,?,?,?,?,?,?)",
          l.claimLineId(),
          l.claimId(),
          l.orderLineId(),
          l.sku(),
          l.requestedQty(),
          l.approvedQty(),
          l.decision(),
          l.lineAmount());
    }
  }

  public Optional<ClaimRow> find(String claimId) {
    return one("SELECT * FROM claim WHERE claim_id = ?", claimId);
  }

  public Optional<ClaimRow> lock(String claimId) {
    return one("SELECT * FROM claim WHERE claim_id = ? FOR UPDATE", claimId);
  }

  public Optional<ClaimRow> lockByStopRequest(String stopRequestId) {
    return one("SELECT * FROM claim WHERE stop_request_id = ? FOR UPDATE", stopRequestId);
  }

  public Optional<ClaimRow> activeCancel(String orderId) {
    return one(
        "SELECT * FROM claim WHERE order_id = ? AND type = 'CANCEL'"
            + " AND state NOT IN ('CLOSED', 'REJECTED', 'WITHDRAWN')",
        orderId);
  }

  /** 목록(최근 접수 순). accountId 가 있으면 그 고객 것만. */
  public List<ClaimRow> list(
      @Nullable String accountId,
      @Nullable String orderId,
      @Nullable String type,
      @Nullable String state,
      int limit) {
    StringBuilder sql = new StringBuilder("SELECT * FROM claim WHERE 1 = 1");
    List<Object> args = new ArrayList<>();
    if (accountId != null) {
      sql.append(" AND account_id = ?");
      args.add(accountId);
    }
    if (orderId != null) {
      sql.append(" AND order_id = ?");
      args.add(orderId);
    }
    if (type != null) {
      sql.append(" AND type = ?");
      args.add(type);
    }
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    sql.append(" ORDER BY opened_at DESC, claim_id DESC LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, i) -> claim(rs), args.toArray());
  }

  public List<LineRow> lines(String claimId) {
    return jdbc.query(
        "SELECT * FROM claim_line WHERE claim_id = ? ORDER BY claim_line_id",
        (rs, i) ->
            new LineRow(
                rs.getString("claim_line_id"),
                rs.getString("claim_id"),
                rs.getString("order_line_id"),
                rs.getString("sku"),
                rs.getInt("requested_qty"),
                (Integer) rs.getObject("approved_qty"),
                rs.getString("decision"),
                rs.getLong("line_amount")),
        claimId);
  }

  public void updateState(
      String claimId,
      String state,
      @Nullable String rejectReason,
      @Nullable Instant decidedAt,
      @Nullable Instant closedAt,
      Instant now) {
    jdbc.update(
        "UPDATE claim SET state = ?, reject_reason = COALESCE(?, reject_reason),"
            + " decided_at = COALESCE(?, decided_at), closed_at = COALESCE(?, closed_at),"
            + " version = version + 1, updated_at = ? WHERE claim_id = ?",
        state,
        rejectReason,
        ts(decidedAt),
        ts(closedAt),
        ts(now),
        claimId);
  }

  /** 행 결정(출고 전 취소 확정 수량). */
  public void decideLine(String claimId, String orderLineId, int approvedQty, String decision) {
    jdbc.update(
        "UPDATE claim_line SET approved_qty = ?, decision = ? WHERE claim_id = ? AND order_line_id = ?",
        approvedQty,
        decision,
        claimId,
        orderLineId);
  }

  // ---------- 환불 의도 ----------

  public void insertRefund(RefundRow r) {
    jdbc.update(
        "INSERT INTO refund_intent (refund_intent_id, claim_id, order_id, payment_attempt_id, scope,"
            + " reason, amount, item_amount, bag_fee, delivery_tip, state, cancellation_request_id,"
            + " attempts, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
        r.refundIntentId(),
        r.claimId(),
        r.orderId(),
        r.paymentAttemptId(),
        r.scope(),
        r.reason(),
        r.amount(),
        r.itemAmount(),
        r.bagFee(),
        r.deliveryTip(),
        r.state(),
        r.cancellationRequestId(),
        ts(r.createdAt()),
        ts(r.updatedAt()));
  }

  public List<RefundRow> refunds(String claimId) {
    return jdbc.query(
        "SELECT * FROM refund_intent WHERE claim_id = ? ORDER BY created_at",
        (rs, i) -> refund(rs),
        claimId);
  }

  public Optional<RefundRow> lockRefund(String refundIntentId) {
    return jdbc
        .query(
            "SELECT * FROM refund_intent WHERE refund_intent_id = ? FOR UPDATE",
            (rs, i) -> refund(rs),
            refundIntentId)
        .stream()
        .findFirst();
  }

  /** 실행 대기·PG 결과 대기·불명이 오래된 환불(재실행·원결과 조회 대상). */
  public List<String> dueRefunds(Instant before) {
    return jdbc.queryForList(
        "SELECT refund_intent_id FROM refund_intent"
            + " WHERE state IN ('PENDING_EXECUTION', 'PG_PENDING', 'UNKNOWN') AND updated_at < ?"
            + " ORDER BY updated_at LIMIT 50",
        String.class,
        ts(before));
  }

  public void updateRefund(
      String refundIntentId,
      String state,
      @Nullable String pgResult,
      @Nullable String pgTransactionId,
      @Nullable String failureCode,
      boolean attempted,
      @Nullable Instant completedAt,
      Instant now) {
    jdbc.update(
        "UPDATE refund_intent SET state = ?, pg_result = COALESCE(?, pg_result),"
            + " pg_transaction_id = COALESCE(?, pg_transaction_id), failure_code = ?,"
            + " attempts = attempts + ?, requested_at = CASE WHEN ? THEN COALESCE(requested_at, ?)"
            + " ELSE requested_at END, completed_at = COALESCE(?, completed_at), updated_at = ?"
            + " WHERE refund_intent_id = ?",
        state,
        pgResult,
        pgTransactionId,
        failureCode,
        attempted ? 1 : 0,
        attempted,
        ts(now),
        ts(completedAt),
        ts(now),
        refundIntentId);
  }

  // ---------- 공통 ----------

  private Optional<ClaimRow> one(String sql, Object arg) {
    return jdbc.query(sql, (rs, i) -> claim(rs), arg).stream().findFirst();
  }

  private static ClaimRow claim(ResultSet rs) throws SQLException {
    return new ClaimRow(
        rs.getString("claim_id"),
        rs.getString("order_id"),
        rs.getString("account_id"),
        rs.getString("type"),
        rs.getString("state"),
        rs.getString("reason"),
        rs.getString("reason_detail"),
        rs.getString("channel"),
        rs.getString("stop_request_id"),
        rs.getInt("version"),
        rs.getString("payment_attempt_id"),
        rs.getLong("approved_amount"),
        rs.getLong("item_amount"),
        rs.getLong("bag_fee"),
        rs.getLong("delivery_tip"),
        rs.getString("reject_reason"),
        rs.getTimestamp("opened_at").toInstant(),
        instant(rs.getTimestamp("decided_at")),
        instant(rs.getTimestamp("closed_at")),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static RefundRow refund(ResultSet rs) throws SQLException {
    return new RefundRow(
        rs.getString("refund_intent_id"),
        rs.getString("claim_id"),
        rs.getString("order_id"),
        rs.getString("payment_attempt_id"),
        rs.getString("scope"),
        rs.getString("reason"),
        rs.getLong("amount"),
        rs.getLong("item_amount"),
        rs.getLong("bag_fee"),
        rs.getLong("delivery_tip"),
        rs.getString("state"),
        rs.getString("cancellation_request_id"),
        rs.getString("pg_result"),
        rs.getString("pg_transaction_id"),
        rs.getString("failure_code"),
        rs.getInt("attempts"),
        instant(rs.getTimestamp("requested_at")),
        instant(rs.getTimestamp("completed_at")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
