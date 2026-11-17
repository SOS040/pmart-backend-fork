package com.pmart.notification.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 리뷰 요청 대기 표(V4 — {@code review_request}, 2026-11-18). 주문 하나에 한 줄. */
@Repository
public class ReviewRequestStore {

  public record Pending(
      String orderId, String accountId, Instant completedAt, String sourceEventId) {}

  /** 시험·점검용 한 줄. */
  public record Row(
      String orderId,
      String accountId,
      Instant completedAt,
      String state,
      int attempts,
      @Nullable String lastError,
      @Nullable String notificationId) {}

  private final JdbcTemplate jdbc;

  public ReviewRequestStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 처음이면 PENDING 으로 넣는다(같은 주문이 이미 있으면 그대로 — 주문당 한 번). 넣었으면 true. */
  public boolean record(Pending p, Instant now) {
    return jdbc.update(
            "INSERT INTO review_request (order_id, account_id, completed_at, source_event_id,"
                + " state, created_at) VALUES (?,?,?,?,'PENDING',?)"
                + " ON CONFLICT (order_id) DO NOTHING",
            p.orderId(),
            p.accountId(),
            Timestamp.from(p.completedAt()),
            p.sourceEventId(),
            Timestamp.from(now))
        > 0;
  }

  /**
   * 오배송 확인(2026-11-18 사용자 "추천대로") — 그 주문의 PENDING 을 ON_HOLD 로(고객이 아직 못 받았다). 이미 보냈거나 끝난 줄은 그대로.
   * 바꿨으면 true.
   */
  public boolean hold(String orderId) {
    return jdbc.update(
            "UPDATE review_request SET state = 'ON_HOLD' WHERE order_id = ? AND state = 'PENDING'",
            orderId)
        > 0;
  }

  /**
   * 보류된 주문의 다음 배송 완료(다시 배송·재발송) — ON_HOLD 를 PENDING 으로 되돌리고 완료 시각·사건을 새것으로(기다림을 다시 센다). 되돌렸으면 true.
   */
  public boolean resume(Pending p) {
    return jdbc.update(
            "UPDATE review_request SET state = 'PENDING', completed_at = ?, source_event_id = ?,"
                + " attempts = 0, last_error = NULL WHERE order_id = ? AND state = 'ON_HOLD'",
            Timestamp.from(p.completedAt()),
            p.sourceEventId(),
            p.orderId())
        > 0;
  }

  /** 완료가 {@code before} 이전인 PENDING 을 모두 EXPIRED 로. 바꾼 수. */
  public int expire(Instant before, Instant now) {
    return jdbc.update(
        "UPDATE review_request SET state = 'EXPIRED', finished_at = ?"
            + " WHERE state = 'PENDING' AND completed_at < ?",
        Timestamp.from(now),
        Timestamp.from(before));
  }

  /** 완료가 {@code before} 이전인(경계 포함) PENDING, 오래된 순. */
  public List<Pending> due(Instant before, int limit) {
    return jdbc.query(
        "SELECT order_id, account_id, completed_at, source_event_id FROM review_request"
            + " WHERE state = 'PENDING' AND completed_at <= ? ORDER BY completed_at, order_id LIMIT ?",
        (rs, i) ->
            new Pending(
                rs.getString("order_id"),
                rs.getString("account_id"),
                rs.getTimestamp("completed_at").toInstant(),
                rs.getString("source_event_id")),
        Timestamp.from(before),
        limit);
  }

  /** PENDING 이면 끝낸다(다른 인스턴스가 먼저 끝냈으면 false). */
  public boolean finish(
      String orderId, String state, @Nullable String notificationId, Instant now) {
    return jdbc.update(
            "UPDATE review_request SET state = ?, notification_id = ?, finished_at = ?,"
                + " last_attempt_at = ? WHERE order_id = ? AND state = 'PENDING'",
            state,
            notificationId,
            Timestamp.from(now),
            Timestamp.from(now),
            orderId)
        > 0;
  }

  /** SENT 줄에 만든 알림 ID 를 적는다. */
  public void attachNotification(String orderId, String notificationId) {
    jdbc.update(
        "UPDATE review_request SET notification_id = ? WHERE order_id = ?",
        notificationId,
        orderId);
  }

  /** review-service 조회 실패 — PENDING 그대로 두고 횟수·코드만. */
  public void failed(String orderId, String errorCode, Instant now) {
    jdbc.update(
        "UPDATE review_request SET attempts = attempts + 1, last_error = ?, last_attempt_at = ?"
            + " WHERE order_id = ? AND state = 'PENDING'",
        errorCode.length() > 40 ? errorCode.substring(0, 40) : errorCode,
        Timestamp.from(now),
        orderId);
  }

  public Optional<Row> find(String orderId) {
    return jdbc
        .query(
            "SELECT * FROM review_request WHERE order_id = ?",
            (rs, i) ->
                new Row(
                    rs.getString("order_id"),
                    rs.getString("account_id"),
                    rs.getTimestamp("completed_at").toInstant(),
                    rs.getString("state"),
                    rs.getInt("attempts"),
                    rs.getString("last_error"),
                    rs.getString("notification_id")),
            orderId)
        .stream()
        .findFirst();
  }
}
