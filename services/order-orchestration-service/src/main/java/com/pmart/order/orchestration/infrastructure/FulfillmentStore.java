package com.pmart.order.orchestration.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** S2 이행 요청 사가(V5, JDBC). */
@Repository
public class FulfillmentStore {

  public record SagaRow(
      String sagaId,
      String orderId,
      String mfcId,
      String reservationId,
      @Nullable Instant slotStart,
      JsonNode request,
      String state,
      String currentStep,
      int workVersion,
      @Nullable Instant firstRejectedAt,
      @Nullable Instant nextCheckAt,
      @Nullable String outcome,
      @Nullable String fulfillmentId,
      @Nullable String claimId,
      int version,
      @Nullable String lastErrorStep,
      @Nullable String lastErrorCode,
      @Nullable String lastErrorMessage,
      Instant startedAt,
      Instant updatedAt,
      @Nullable Instant finishedAt) {}

  public record AttemptRow(
      String sagaId,
      int workVersion,
      String requestId,
      @Nullable String decision,
      @Nullable String rejectReason,
      Instant requestedAt,
      @Nullable Instant decidedAt) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public FulfillmentStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  /** 주문당 사가 하나. 이미 있으면 false(재전달·재처리). */
  public boolean insert(SagaRow s) {
    return jdbc.update(
            "INSERT INTO fulfillment_saga (saga_id, order_id, mfc_id, reservation_id, slot_start,"
                + " request, state, current_step, work_version, started_at, updated_at)"
                + " VALUES (?,?,?,?,?,?::jsonb,?,?,?,?,?) ON CONFLICT (order_id) DO NOTHING",
            s.sagaId(),
            s.orderId(),
            s.mfcId(),
            s.reservationId(),
            ts(s.slotStart()),
            s.request().toString(),
            s.state(),
            s.currentStep(),
            s.workVersion(),
            ts(s.startedAt()),
            ts(s.updatedAt()))
        == 1;
  }

  public Optional<SagaRow> find(String sagaId) {
    return one("SELECT * FROM fulfillment_saga WHERE saga_id = ?", sagaId);
  }

  public Optional<SagaRow> lock(String sagaId) {
    return one("SELECT * FROM fulfillment_saga WHERE saga_id = ? FOR UPDATE", sagaId);
  }

  public Optional<SagaRow> byOrder(String orderId) {
    return one("SELECT * FROM fulfillment_saga WHERE order_id = ?", orderId);
  }

  public List<SagaRow> list(@Nullable String orderId, @Nullable String state, int size) {
    return jdbc.query(
        "SELECT * FROM fulfillment_saga WHERE (?::text IS NULL OR order_id = ?)"
            + " AND (?::text IS NULL OR state = ?) ORDER BY started_at DESC LIMIT ?",
        (rs, i) -> row(rs),
        orderId,
        orderId,
        state,
        state,
        size);
  }

  /** 재개 대상: 멈춘 RUNNING, 재확인 시각이 된 RETRY_WAITING, 설정 대기 CONFIG_REQUIRED(설정이 들어왔는지 본다). */
  public List<String> due(Instant now, Instant stuckBefore, Instant configBefore) {
    return jdbc.queryForList(
        "SELECT saga_id FROM fulfillment_saga WHERE (state = 'RUNNING' AND updated_at < ?)"
            + " OR (state = 'RETRY_WAITING' AND next_check_at <= ?)"
            + " OR (state = 'CONFIG_REQUIRED' AND updated_at < ?) ORDER BY updated_at LIMIT 50",
        String.class,
        ts(stuckBefore),
        ts(now),
        ts(configBefore));
  }

  /** 상태·단계·대기 시각 갱신(버전 +1). */
  public void move(
      String sagaId,
      String state,
      String currentStep,
      int workVersion,
      @Nullable Instant firstRejectedAt,
      @Nullable Instant nextCheckAt,
      Instant now) {
    jdbc.update(
        "UPDATE fulfillment_saga SET state = ?, current_step = ?, work_version = ?,"
            + " first_rejected_at = ?, next_check_at = ?, version = version + 1, updated_at = ?"
            + " WHERE saga_id = ?",
        state,
        currentStep,
        workVersion,
        ts(firstRejectedAt),
        ts(nextCheckAt),
        ts(now),
        sagaId);
  }

  public void finish(
      String sagaId,
      String state,
      @Nullable String outcome,
      @Nullable String fulfillmentId,
      @Nullable String claimId,
      @Nullable String errorStep,
      @Nullable String errorCode,
      @Nullable String errorMessage,
      Instant now) {
    jdbc.update(
        "UPDATE fulfillment_saga SET state = ?, outcome = ?, fulfillment_id = COALESCE(?, fulfillment_id),"
            + " claim_id = COALESCE(?, claim_id), last_error_step = ?, last_error_code = ?,"
            + " last_error_message = ?, next_check_at = NULL, version = version + 1, updated_at = ?,"
            + " finished_at = ? WHERE saga_id = ?",
        state,
        outcome,
        fulfillmentId,
        claimId,
        errorStep,
        errorCode,
        errorMessage == null || errorMessage.length() <= 500
            ? errorMessage
            : errorMessage.substring(0, 500),
        ts(now),
        "ESCALATED".equals(state) ? null : ts(now),
        sagaId);
  }

  public void setFulfillmentId(String sagaId, String fulfillmentId) {
    jdbc.update(
        "UPDATE fulfillment_saga SET fulfillment_id = ? WHERE saga_id = ?", fulfillmentId, sagaId);
  }

  public void touch(String sagaId, Instant now) {
    jdbc.update("UPDATE fulfillment_saga SET updated_at = ? WHERE saga_id = ?", ts(now), sagaId);
  }

  // ---------- 시도 ----------

  public void startAttempt(String sagaId, int workVersion, String requestId, Instant now) {
    jdbc.update(
        "INSERT INTO fulfillment_attempt (saga_id, work_version, request_id, requested_at)"
            + " VALUES (?,?,?,?) ON CONFLICT (saga_id, work_version) DO NOTHING",
        sagaId,
        workVersion,
        requestId,
        ts(now));
  }

  public void decideAttempt(
      String sagaId, int workVersion, String decision, @Nullable String rejectReason, Instant now) {
    jdbc.update(
        "UPDATE fulfillment_attempt SET decision = ?, reject_reason = ?, decided_at = ?"
            + " WHERE saga_id = ? AND work_version = ?",
        decision,
        rejectReason,
        ts(now),
        sagaId,
        workVersion);
  }

  public List<AttemptRow> attempts(String sagaId) {
    return jdbc.query(
        "SELECT * FROM fulfillment_attempt WHERE saga_id = ? ORDER BY work_version",
        (rs, i) ->
            new AttemptRow(
                rs.getString("saga_id"),
                rs.getInt("work_version"),
                rs.getString("request_id"),
                rs.getString("decision"),
                rs.getString("reject_reason"),
                rs.getTimestamp("requested_at").toInstant(),
                instant(rs, "decided_at")),
        sagaId);
  }

  // ---------- 변환 ----------

  private Optional<SagaRow> one(String sql, Object arg) {
    return jdbc.query(sql, (rs, i) -> row(rs), arg).stream().findFirst();
  }

  private SagaRow row(ResultSet rs) throws SQLException {
    return new SagaRow(
        rs.getString("saga_id"),
        rs.getString("order_id"),
        rs.getString("mfc_id"),
        rs.getString("reservation_id"),
        instant(rs, "slot_start"),
        mapper.readTree(rs.getString("request")),
        rs.getString("state"),
        rs.getString("current_step"),
        rs.getInt("work_version"),
        instant(rs, "first_rejected_at"),
        instant(rs, "next_check_at"),
        rs.getString("outcome"),
        rs.getString("fulfillment_id"),
        rs.getString("claim_id"),
        rs.getInt("version"),
        rs.getString("last_error_step"),
        rs.getString("last_error_code"),
        rs.getString("last_error_message"),
        rs.getTimestamp("started_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        instant(rs, "finished_at"));
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
