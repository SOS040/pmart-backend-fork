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

/** 결품 사가(V6, JDBC). */
@Repository
public class ShortageStore {

  public record ShortageRow(
      String sagaId,
      String eventId,
      String orderId,
      String fulfillmentId,
      @Nullable String reservationId,
      String orderLineId,
      @Nullable String sku,
      int shortQty,
      @Nullable String reason,
      @Nullable String preference,
      String state,
      String currentStep,
      @Nullable String claimId,
      @Nullable String lastErrorStep,
      @Nullable String lastErrorCode,
      @Nullable String lastErrorMessage,
      Instant startedAt,
      Instant updatedAt,
      @Nullable Instant finishedAt) {}

  private final JdbcTemplate jdbc;

  public ShortageStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 같은 사건이면 만들지 않는다. true = 새로 만듦. */
  public boolean insert(ShortageRow r) {
    return jdbc.update(
            "INSERT INTO shortage_saga (saga_id, event_id, order_id, fulfillment_id, reservation_id,"
                + " order_line_id, sku, short_qty, reason, preference, state, current_step,"
                + " last_error_step, last_error_code, last_error_message, started_at, updated_at,"
                + " finished_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (event_id) DO NOTHING",
            r.sagaId(),
            r.eventId(),
            r.orderId(),
            r.fulfillmentId(),
            r.reservationId(),
            r.orderLineId(),
            r.sku(),
            r.shortQty(),
            r.reason(),
            r.preference(),
            r.state(),
            r.currentStep(),
            r.lastErrorStep(),
            r.lastErrorCode(),
            r.lastErrorMessage(),
            Timestamp.from(r.startedAt()),
            Timestamp.from(r.updatedAt()),
            r.finishedAt() == null ? null : Timestamp.from(r.finishedAt()))
        == 1;
  }

  public Optional<ShortageRow> find(String sagaId) {
    return jdbc
        .query("SELECT * FROM shortage_saga WHERE saga_id = ?", (rs, i) -> row(rs), sagaId)
        .stream()
        .findFirst();
  }

  public Optional<ShortageRow> lock(String sagaId) {
    return jdbc
        .query(
            "SELECT * FROM shortage_saga WHERE saga_id = ? FOR UPDATE SKIP LOCKED",
            (rs, i) -> row(rs),
            sagaId)
        .stream()
        .findFirst();
  }

  public List<ShortageRow> byOrder(String orderId) {
    return jdbc.query(
        "SELECT * FROM shortage_saga WHERE order_id = ? ORDER BY started_at",
        (rs, i) -> row(rs),
        orderId);
  }

  public List<String> due(Instant stuckBefore) {
    return jdbc.queryForList(
        "SELECT saga_id FROM shortage_saga WHERE state = 'RUNNING' AND updated_at < ?"
            + " ORDER BY updated_at LIMIT 100",
        String.class,
        Timestamp.from(stuckBefore));
  }

  public void step(String sagaId, String step, Instant at) {
    jdbc.update(
        "UPDATE shortage_saga SET current_step = ?, updated_at = ? WHERE saga_id = ?",
        step,
        Timestamp.from(at),
        sagaId);
  }

  public void touch(String sagaId, Instant at) {
    jdbc.update(
        "UPDATE shortage_saga SET updated_at = ? WHERE saga_id = ?", Timestamp.from(at), sagaId);
  }

  public void finish(
      String sagaId,
      String state,
      @Nullable String claimId,
      @Nullable String errorStep,
      @Nullable String errorCode,
      @Nullable String errorMessage,
      Instant at) {
    jdbc.update(
        "UPDATE shortage_saga SET state = ?, claim_id = COALESCE(?, claim_id), last_error_step = ?,"
            + " last_error_code = ?, last_error_message = ?, updated_at = ?, finished_at = ?"
            + " WHERE saga_id = ?",
        state,
        claimId,
        errorStep,
        errorCode,
        errorMessage == null
            ? null
            : errorMessage.substring(0, Math.min(500, errorMessage.length())),
        Timestamp.from(at),
        Timestamp.from(at),
        sagaId);
  }

  private static ShortageRow row(ResultSet rs) throws SQLException {
    Timestamp f = rs.getTimestamp("finished_at");
    return new ShortageRow(
        rs.getString("saga_id"),
        rs.getString("event_id"),
        rs.getString("order_id"),
        rs.getString("fulfillment_id"),
        rs.getString("reservation_id"),
        rs.getString("order_line_id"),
        rs.getString("sku"),
        rs.getInt("short_qty"),
        rs.getString("reason"),
        rs.getString("preference"),
        rs.getString("state"),
        rs.getString("current_step"),
        rs.getString("claim_id"),
        rs.getString("last_error_step"),
        rs.getString("last_error_code"),
        rs.getString("last_error_message"),
        rs.getTimestamp("started_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        f == null ? null : f.toInstant());
  }
}
