package com.pmart.notification.infrastructure;

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

/** 알림함 표(V2 — {@code notification}·{@code refund_link}·{@code pending_refund_result}). */
@Repository
public class NotificationStore {

  public record NotificationRow(
      String notificationId,
      String recipientType,
      String recipientId,
      String category,
      String templateKey,
      String title,
      @Nullable String body,
      @Nullable String destinationType,
      @Nullable String destinationRef,
      @Nullable String sourceEventId,
      @Nullable String sourceEventType,
      String state,
      @Nullable Instant occurredAt,
      Instant createdAt,
      @Nullable Instant readAt) {}

  public record RefundLink(String refundIntentId, String orderId, @Nullable String claimId) {}

  public record PendingResult(
      String refundIntentId,
      String sourceEventId,
      String result,
      @Nullable Long amount,
      @Nullable Instant resultAt) {}

  private final JdbcTemplate jdbc;

  public NotificationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 같은 dedupe_key 가 있으면 넣지 않는다. 넣었으면 true. */
  public boolean insert(NotificationRow n, String dedupeKey) {
    return jdbc.update(
            "INSERT INTO notification (notification_id, recipient_type, recipient_id, category,"
                + " template_key, title, body, destination_type, destination_ref, source_event_id,"
                + " source_event_type, dedupe_key, state, occurred_at, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (dedupe_key) DO NOTHING",
            n.notificationId(),
            n.recipientType(),
            n.recipientId(),
            n.category(),
            n.templateKey(),
            n.title(),
            n.body(),
            n.destinationType(),
            n.destinationRef(),
            n.sourceEventId(),
            n.sourceEventType(),
            dedupeKey,
            n.state(),
            ts(n.occurredAt()),
            ts(n.createdAt()))
        > 0;
  }

  public List<NotificationRow> page(
      String recipientType,
      String recipientId,
      boolean unreadOnly,
      @Nullable String category,
      int offset,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT * FROM notification WHERE recipient_type = ? AND recipient_id = ?");
    List<Object> args = new ArrayList<>(List.of(recipientType, recipientId));
    if (unreadOnly) {
      sql.append(" AND state = 'NOTICE_UNREAD'");
    }
    if (category != null) {
      sql.append(" AND category = ?");
      args.add(category);
    }
    sql.append(" ORDER BY created_at DESC, notification_id DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray());
  }

  public int unreadCount(String recipientType, String recipientId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notification WHERE recipient_type = ? AND recipient_id = ?"
            + " AND state = 'NOTICE_UNREAD'",
        Integer.class,
        recipientType,
        recipientId);
  }

  public Optional<NotificationRow> find(String notificationId) {
    return jdbc
        .query(
            "SELECT * FROM notification WHERE notification_id = ?",
            (rs, i) -> row(rs),
            notificationId)
        .stream()
        .findFirst();
  }

  /** 안 읽은 것만 읽음으로(이미 읽었으면 그대로). */
  public void markRead(String notificationId, Instant now) {
    jdbc.update(
        "UPDATE notification SET state = 'NOTICE_READ', read_at = ?"
            + " WHERE notification_id = ? AND state = 'NOTICE_UNREAD'",
        ts(now),
        notificationId);
  }

  // ---------------------------------------------------------------- 환불 잇기

  /** 같은 환불 의도의 의도·결과 처리를 트랜잭션 끝까지 한 줄로 세운다(두 사건이 다른 토픽에서 동시에 오면 서로를 못 보고 결과가 남는 일을 막는다). */
  public void lockRefund(String refundIntentId) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, "refund:" + refundIntentId);
  }

  public void saveRefundLink(RefundLink l, Instant now) {
    jdbc.update(
        "INSERT INTO refund_link (refund_intent_id, order_id, claim_id, created_at)"
            + " VALUES (?,?,?,?) ON CONFLICT (refund_intent_id) DO NOTHING",
        l.refundIntentId(),
        l.orderId(),
        l.claimId(),
        ts(now));
  }

  public Optional<RefundLink> refundLink(String refundIntentId) {
    return jdbc
        .query(
            "SELECT * FROM refund_link WHERE refund_intent_id = ?",
            (rs, i) ->
                new RefundLink(
                    rs.getString("refund_intent_id"),
                    rs.getString("order_id"),
                    rs.getString("claim_id")),
            refundIntentId)
        .stream()
        .findFirst();
  }

  public void savePending(PendingResult p, Instant now) {
    jdbc.update(
        "INSERT INTO pending_refund_result (refund_intent_id, source_event_id, result, amount,"
            + " result_at, created_at) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        p.refundIntentId(),
        p.sourceEventId(),
        p.result(),
        p.amount(),
        ts(p.resultAt()),
        ts(now));
  }

  /** 기다리던 결과를 꺼내고 지운다. */
  public List<PendingResult> takePending(String refundIntentId) {
    List<PendingResult> rows =
        jdbc.query(
            "SELECT * FROM pending_refund_result WHERE refund_intent_id = ? FOR UPDATE",
            (rs, i) ->
                new PendingResult(
                    rs.getString("refund_intent_id"),
                    rs.getString("source_event_id"),
                    rs.getString("result"),
                    (Long) rs.getObject("amount", Long.class),
                    instant(rs, "result_at")),
            refundIntentId);
    jdbc.update("DELETE FROM pending_refund_result WHERE refund_intent_id = ?", refundIntentId);
    return rows;
  }

  private static NotificationRow row(ResultSet rs) throws SQLException {
    return new NotificationRow(
        rs.getString("notification_id"),
        rs.getString("recipient_type"),
        rs.getString("recipient_id"),
        rs.getString("category"),
        rs.getString("template_key"),
        rs.getString("title"),
        rs.getString("body"),
        rs.getString("destination_type"),
        rs.getString("destination_ref"),
        rs.getString("source_event_id"),
        rs.getString("source_event_type"),
        rs.getString("state"),
        instant(rs, "occurred_at"),
        rs.getTimestamp("created_at").toInstant(),
        instant(rs, "read_at"));
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
