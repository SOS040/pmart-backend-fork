package com.pmart.payment.infrastructure;

import com.pmart.payment.domain.PaymentStage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 결제 시도·취소·토스 호출 기록·원거래 조회·웹훅 저장(JDBC). 시크릿 키·카드 정보는 다루지 않는다. */
@Repository
public class PaymentStore {

  public record Attempt(
      String id,
      String orderRef,
      @Nullable String draftRef,
      String sagaId,
      String customerRef,
      String sourceSystem,
      String entryRequestId,
      long amount,
      String currency,
      @Nullable String orderName,
      boolean requiresAdult,
      PaymentStage stage,
      @Nullable String paymentKey,
      String confirmIdempotencyKey,
      @Nullable Instant confirmCalledAt,
      Instant resourceExpiresAt,
      @Nullable Instant priceValidUntil,
      @Nullable String tossStatus,
      @Nullable String method,
      @Nullable Instant approvedAt,
      @Nullable Long totalAmount,
      @Nullable Long balanceAmount,
      @Nullable Boolean partialCancelable,
      @Nullable String failStage,
      @Nullable String failCode,
      @Nullable String failMessage,
      @Nullable String mismatchField,
      int unknownRetryCount,
      @Nullable Instant nextCheckAt,
      @Nullable Instant lastInquiryAt,
      int version,
      Instant createdAt) {}

  public record Cancel(
      String requestId,
      String attemptId,
      String kind,
      @Nullable String refundIntentId,
      @Nullable String claimRef,
      @Nullable String sagaId,
      long amount,
      String scope,
      String reason,
      String state,
      @Nullable String transactionKey,
      @Nullable String pgResultCode,
      @Nullable Instant canceledAt,
      @Nullable String resolvedBy,
      int unknownRetryCount,
      Instant requestedAt,
      @Nullable Instant resolvedAt) {}

  /** 토스 응답에서 갱신하는 값. */
  public record TossFacts(
      @Nullable String tossStatus,
      @Nullable String method,
      @Nullable Instant approvedAt,
      @Nullable Long totalAmount,
      @Nullable Long balanceAmount,
      @Nullable Boolean partialCancelable) {}

  private final JdbcTemplate jdbc;

  public PaymentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 결제 시도 ----------

  public void insert(Attempt a) {
    jdbc.update(
        "INSERT INTO payment_attempt (payment_attempt_id, order_ref, draft_ref, saga_id, customer_ref,"
            + " source_system, entry_request_id, amount, currency, order_name, requires_adult, stage,"
            + " confirm_idempotency_key, resource_expires_at, price_valid_until, unknown_retry_count,"
            + " version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,1,?,?)",
        a.id(),
        a.orderRef(),
        a.draftRef(),
        a.sagaId(),
        a.customerRef(),
        a.sourceSystem(),
        a.entryRequestId(),
        a.amount(),
        a.currency(),
        a.orderName(),
        a.requiresAdult(),
        a.stage().name(),
        a.confirmIdempotencyKey(),
        ts(a.resourceExpiresAt()),
        ts(a.priceValidUntil()),
        ts(a.createdAt()),
        ts(a.createdAt()));
  }

  public Optional<Attempt> find(String id, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT * FROM payment_attempt WHERE payment_attempt_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> attempt(rs),
            id)
        .stream()
        .findFirst();
  }

  public Optional<Attempt> byEntry(String sourceSystem, String entryRequestId) {
    return jdbc
        .query(
            "SELECT * FROM payment_attempt WHERE source_system = ? AND entry_request_id = ?",
            (rs, i) -> attempt(rs),
            sourceSystem,
            entryRequestId)
        .stream()
        .findFirst();
  }

  public List<Attempt> byOrder(String orderRef) {
    return jdbc.query(
        "SELECT * FROM payment_attempt WHERE order_ref = ? ORDER BY created_at",
        (rs, i) -> attempt(rs),
        orderRef);
  }

  /** 같은 주문의 결제 시작을 직렬화(R7 판정 경합 방지). */
  public void lockOrder(String orderRef) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, "payment-order:" + orderRef);
  }

  /** 조건부 단계 전이. 바뀌었으면 true. */
  public boolean transition(String id, PaymentStage from, PaymentStage to, Instant at) {
    return jdbc.update(
            "UPDATE payment_attempt SET stage = ?, version = version + 1, updated_at = ?"
                + " WHERE payment_attempt_id = ? AND stage = ?",
            to.name(),
            ts(at),
            id,
            from.name())
        == 1;
  }

  /** READY → CONFIRMING 과 paymentKey 저장(R8), 승인 호출 시각 기록. 동시 요청 중 하나만 성공. */
  public boolean beginConfirm(String id, String paymentKey, Instant at) {
    return jdbc.update(
            "UPDATE payment_attempt SET stage = 'CONFIRMING', payment_key = ?,"
                + " confirm_called_at = COALESCE(confirm_called_at, ?), version = version + 1, updated_at = ?"
                + " WHERE payment_attempt_id = ? AND stage = 'READY'",
            paymentKey,
            ts(at),
            ts(at),
            id)
        == 1;
  }

  public void setStage(String id, PaymentStage to, Instant at) {
    jdbc.update(
        "UPDATE payment_attempt SET stage = ?, version = version + 1, updated_at = ? WHERE payment_attempt_id = ?",
        to.name(),
        ts(at),
        id);
  }

  public void recordToss(String id, TossFacts f, Instant at) {
    jdbc.update(
        "UPDATE payment_attempt SET toss_status = COALESCE(?, toss_status), method = COALESCE(?, method),"
            + " approved_at = COALESCE(?, approved_at), total_amount = COALESCE(?, total_amount),"
            + " balance_amount = COALESCE(?, balance_amount),"
            + " is_partial_cancelable = COALESCE(?, is_partial_cancelable), updated_at = ?"
            + " WHERE payment_attempt_id = ?",
        f.tossStatus(),
        f.method(),
        ts(f.approvedAt()),
        f.totalAmount(),
        f.balanceAmount(),
        f.partialCancelable(),
        ts(at),
        id);
  }

  public void recordFailure(
      String id, String stage, @Nullable String code, @Nullable String message, Instant at) {
    jdbc.update(
        "UPDATE payment_attempt SET fail_stage = ?, fail_code = ?, fail_message = ?, updated_at = ?"
            + " WHERE payment_attempt_id = ?",
        stage,
        truncate(code, 100),
        truncate(message, 500),
        ts(at),
        id);
  }

  public void recordMismatch(String id, String field, Instant at) {
    jdbc.update(
        "UPDATE payment_attempt SET mismatch_field = ?, updated_at = ? WHERE payment_attempt_id = ?",
        field,
        ts(at),
        id);
  }

  public void schedule(String id, int retryCount, @Nullable Instant nextCheckAt, Instant at) {
    jdbc.update(
        "UPDATE payment_attempt SET unknown_retry_count = ?, next_check_at = ?, last_inquiry_at = ?,"
            + " updated_at = ? WHERE payment_attempt_id = ?",
        retryCount,
        ts(nextCheckAt),
        ts(at),
        ts(at),
        id);
  }

  public void clearSchedule(String id, Instant at) {
    jdbc.update(
        "UPDATE payment_attempt SET next_check_at = NULL, last_inquiry_at = ?, updated_at = ?"
            + " WHERE payment_attempt_id = ?",
        ts(at),
        ts(at),
        id);
  }

  /** 웹훅 등으로 대사를 앞당긴다(결과를 기다리는 단계와 취소될 수 있는 승인 단계). */
  public void expedite(String id, Instant at) {
    jdbc.update(
        "UPDATE payment_attempt SET next_check_at = ? WHERE payment_attempt_id = ?"
            + " AND stage IN ('CONFIRMING', 'CONFIRM_UNKNOWN', 'MANUAL_REVIEW', 'APPROVED', 'PARTIAL_CANCELED')",
        ts(at),
        id);
  }

  /** 재조회할 시도를 잡는다(임대: next_check_at 을 미뤄 다른 인스턴스와 겹치지 않게). */
  public List<String> leaseDue(Instant now, Instant leaseUntil, int limit) {
    return jdbc.queryForList(
        "UPDATE payment_attempt SET next_check_at = ? WHERE payment_attempt_id IN ("
            + " SELECT payment_attempt_id FROM payment_attempt WHERE next_check_at <= ?"
            + " ORDER BY next_check_at LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING payment_attempt_id",
        String.class,
        ts(leaseUntil),
        ts(now),
        limit);
  }

  /** 승인 호출 없이 예약이 만료된 READY 시도. */
  public List<String> lockExpiredReady(Instant now, int limit) {
    return jdbc.queryForList(
        "SELECT payment_attempt_id FROM payment_attempt WHERE stage = 'READY' AND confirm_called_at IS NULL"
            + " AND resource_expires_at <= ? ORDER BY resource_expires_at LIMIT ? FOR UPDATE SKIP LOCKED",
        String.class,
        ts(now),
        limit);
  }

  // ---------- 취소 ----------

  public void insertCancel(Cancel c, @Nullable Long refundableSent) {
    jdbc.update(
        "INSERT INTO payment_cancel (request_id, payment_attempt_id, kind, refund_intent_id, claim_ref,"
            + " saga_id, amount, scope, reason, refundable_amount_sent, state, unknown_retry_count,"
            + " requested_at) VALUES (?,?,?,?,?,?,?,?,?,?,'REQUESTED',0,?)",
        c.requestId(),
        c.attemptId(),
        c.kind(),
        c.refundIntentId(),
        c.claimRef(),
        c.sagaId(),
        c.amount(),
        c.scope(),
        c.reason(),
        refundableSent,
        ts(c.requestedAt()));
  }

  public Optional<Cancel> cancel(String requestId) {
    return jdbc
        .query(
            "SELECT * FROM payment_cancel WHERE request_id = ?", (rs, i) -> cancel(rs), requestId)
        .stream()
        .findFirst();
  }

  public boolean refundIntentUsed(String refundIntentId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM payment_cancel WHERE refund_intent_id = ?",
            Integer.class,
            refundIntentId);
    return n != null && n > 0;
  }

  public List<Cancel> cancelsOf(String attemptId) {
    return jdbc.query(
        "SELECT * FROM payment_cancel WHERE payment_attempt_id = ? ORDER BY requested_at",
        (rs, i) -> cancel(rs),
        attemptId);
  }

  public void resolveCancel(
      String requestId,
      String state,
      @Nullable String transactionKey,
      @Nullable String code,
      @Nullable Instant canceledAt,
      String resolvedBy,
      Instant at) {
    jdbc.update(
        "UPDATE payment_cancel SET state = ?, transaction_key = COALESCE(?, transaction_key),"
            + " pg_result_code = ?, canceled_at = ?, resolved_by = ?, resolved_at = ?, next_check_at = NULL"
            + " WHERE request_id = ?",
        state,
        transactionKey,
        truncate(code, 100),
        ts(canceledAt),
        resolvedBy,
        ts(at),
        requestId);
  }

  public void markCancelUnknown(String requestId, int retryCount, Instant next) {
    jdbc.update(
        "UPDATE payment_cancel SET state = 'UNKNOWN', unknown_retry_count = ?, next_check_at = ?"
            + " WHERE request_id = ?",
        retryCount,
        ts(next),
        requestId);
  }

  public List<String> leaseDueCancels(Instant now, Instant leaseUntil, int limit) {
    return jdbc.queryForList(
        "UPDATE payment_cancel SET next_check_at = ? WHERE request_id IN ("
            + " SELECT request_id FROM payment_cancel WHERE next_check_at <= ?"
            + " ORDER BY next_check_at LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING request_id",
        String.class,
        ts(leaseUntil),
        ts(now),
        limit);
  }

  // ---------- 감사·조회·웹훅 ----------

  public void log(
      String attemptId,
      String action,
      @Nullable Integer httpStatus,
      @Nullable String tossCode,
      @Nullable PaymentStage from,
      @Nullable PaymentStage to,
      String actor,
      @Nullable String detail,
      Instant at) {
    jdbc.update(
        "INSERT INTO payment_event_log (payment_attempt_id, action, http_status, toss_code, from_stage,"
            + " to_stage, actor, detail, occurred_at) VALUES (?,?,?,?,?,?,?,?,?)",
        attemptId,
        action,
        httpStatus,
        truncate(tossCode, 100),
        from == null ? null : from.name(),
        to == null ? null : to.name(),
        truncate(actor, 64),
        truncate(detail, 500),
        ts(at));
  }

  public int insertInquiry(
      String attemptId,
      String requestId,
      String reason,
      String result,
      @Nullable String tossStatus,
      Instant at) {
    return jdbc.update(
        "INSERT INTO payment_inquiry (request_id, payment_attempt_id, reason, result, toss_status, inquired_at)"
            + " VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        requestId,
        attemptId,
        reason,
        result,
        tossStatus,
        ts(at));
  }

  public record InquiryRow(
      String requestId, String attemptId, String reason, String result, Instant inquiredAt) {}

  public Optional<InquiryRow> inquiry(String attemptId, String requestId) {
    return jdbc
        .query(
            "SELECT * FROM payment_inquiry WHERE payment_attempt_id = ? AND request_id = ?",
            (rs, i) ->
                new InquiryRow(
                    rs.getString("request_id"),
                    rs.getString("payment_attempt_id"),
                    rs.getString("reason"),
                    rs.getString("result"),
                    rs.getTimestamp("inquired_at").toInstant()),
            attemptId,
            requestId)
        .stream()
        .findFirst();
  }

  /** 처음 받은 웹훅이면 true. */
  public boolean recordWebhook(
      String bodyHash, String eventType, @Nullable String attemptId, Instant at) {
    return jdbc.update(
            "INSERT INTO toss_webhook_event (body_hash, event_type, payment_attempt_id, received_at)"
                + " VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
            bodyHash,
            truncate(eventType, 64),
            attemptId,
            ts(at))
        == 1;
  }

  private static Attempt attempt(ResultSet rs) throws SQLException {
    return new Attempt(
        rs.getString("payment_attempt_id"),
        rs.getString("order_ref"),
        rs.getString("draft_ref"),
        rs.getString("saga_id"),
        rs.getString("customer_ref"),
        rs.getString("source_system"),
        rs.getString("entry_request_id"),
        rs.getLong("amount"),
        rs.getString("currency"),
        rs.getString("order_name"),
        rs.getBoolean("requires_adult"),
        PaymentStage.valueOf(rs.getString("stage")),
        rs.getString("payment_key"),
        rs.getString("confirm_idempotency_key"),
        instant(rs, "confirm_called_at"),
        rs.getTimestamp("resource_expires_at").toInstant(),
        instant(rs, "price_valid_until"),
        rs.getString("toss_status"),
        rs.getString("method"),
        instant(rs, "approved_at"),
        rs.getObject("total_amount", Long.class),
        rs.getObject("balance_amount", Long.class),
        rs.getObject("is_partial_cancelable", Boolean.class),
        rs.getString("fail_stage"),
        rs.getString("fail_code"),
        rs.getString("fail_message"),
        rs.getString("mismatch_field"),
        rs.getInt("unknown_retry_count"),
        instant(rs, "next_check_at"),
        instant(rs, "last_inquiry_at"),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static Cancel cancel(ResultSet rs) throws SQLException {
    return new Cancel(
        rs.getString("request_id"),
        rs.getString("payment_attempt_id"),
        rs.getString("kind"),
        rs.getString("refund_intent_id"),
        rs.getString("claim_ref"),
        rs.getString("saga_id"),
        rs.getLong("amount"),
        rs.getString("scope"),
        rs.getString("reason"),
        rs.getString("state"),
        rs.getString("transaction_key"),
        rs.getString("pg_result_code"),
        instant(rs, "canceled_at"),
        rs.getString("resolved_by"),
        rs.getInt("unknown_retry_count"),
        rs.getTimestamp("requested_at").toInstant(),
        instant(rs, "resolved_at"));
  }

  private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static @Nullable String truncate(@Nullable String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
