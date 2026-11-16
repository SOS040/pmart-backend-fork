package com.pmart.order.orchestration.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** S4 예약 시간 변경 사가(V12 time_change_saga). */
@Repository
public class TimeChangeStore {

  public record Row(
      String sagaId,
      String requestId,
      String orderId,
      String mfcId,
      LocalDate oldDate,
      String oldStart,
      String oldEnd,
      LocalDate newDate,
      String newStart,
      String newEnd,
      String state,
      String currentStep,
      @Nullable String outcome,
      @Nullable String reason,
      @Nullable String checkoutSagaId,
      @Nullable String reservationId,
      @Nullable String fulfillmentId,
      @Nullable String oldSlotHoldId,
      @Nullable String newSlotHoldId,
      @Nullable String oldCapacityHoldId,
      @Nullable String newCapacityHoldId,
      boolean lotMoved) {

    public boolean dateChanged() {
      return !oldDate.equals(newDate);
    }
  }

  private final JdbcTemplate jdbc;

  public TimeChangeStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 같은 요청이면 false. 주문에 진행 중 사가가 있으면 유일 색인 예외. */
  public boolean insert(
      String sagaId,
      String requestId,
      String orderId,
      String mfcId,
      LocalDate oldDate,
      String oldStart,
      String oldEnd,
      LocalDate newDate,
      String newStart,
      String newEnd,
      Instant now) {
    return jdbc.update(
            "INSERT INTO time_change_saga (saga_id, request_id, order_id, mfc_id, old_date,"
                + " old_start, old_end, new_date, new_start, new_end, state, current_step,"
                + " started_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,'RUNNING','FIND_RESOURCES',?,?)"
                + " ON CONFLICT (request_id) DO NOTHING",
            sagaId,
            requestId,
            orderId,
            mfcId,
            Date.valueOf(oldDate),
            oldStart,
            oldEnd,
            Date.valueOf(newDate),
            newStart,
            newEnd,
            Timestamp.from(now),
            Timestamp.from(now))
        == 1;
  }

  public Optional<Row> find(String sagaId) {
    return jdbc
        .query("SELECT * FROM time_change_saga WHERE saga_id = ?", this::row, sagaId)
        .stream()
        .findFirst();
  }

  public Optional<Row> byRequest(String requestId) {
    return jdbc
        .query("SELECT * FROM time_change_saga WHERE request_id = ?", this::row, requestId)
        .stream()
        .findFirst();
  }

  public Optional<Row> lock(String sagaId) {
    return jdbc
        .query("SELECT * FROM time_change_saga WHERE saga_id = ? FOR UPDATE", this::row, sagaId)
        .stream()
        .findFirst();
  }

  /** 주문의 마지막으로 확정된 시간 변경(지금 점유가 그 사가의 새 점유다). */
  public Optional<Row> lastConfirmed(String orderId) {
    return jdbc
        .query(
            "SELECT * FROM time_change_saga WHERE order_id = ? AND outcome = 'CONFIRMED' AND state ="
                + " 'COMPLETED' ORDER BY finished_at DESC LIMIT 1",
            this::row,
            orderId)
        .stream()
        .findFirst();
  }

  public List<String> due(Instant before) {
    return jdbc.queryForList(
        "SELECT saga_id FROM time_change_saga WHERE state = 'RUNNING' AND updated_at < ? ORDER BY"
            + " updated_at LIMIT 100",
        String.class,
        Timestamp.from(before));
  }

  public void resources(
      String sagaId,
      String checkoutSagaId,
      @Nullable String reservationId,
      String fulfillmentId,
      String oldSlotHoldId,
      String oldCapacityHoldId,
      Instant now) {
    jdbc.update(
        "UPDATE time_change_saga SET checkout_saga_id = ?, reservation_id = ?, fulfillment_id = ?,"
            + " old_slot_hold_id = ?, old_capacity_hold_id = ?, current_step = 'HOLD_SLOT',"
            + " updated_at = ? WHERE saga_id = ?",
        checkoutSagaId,
        reservationId,
        fulfillmentId,
        oldSlotHoldId,
        oldCapacityHoldId,
        Timestamp.from(now),
        sagaId);
  }

  public void newSlot(String sagaId, String holdId, String next, Instant now) {
    jdbc.update(
        "UPDATE time_change_saga SET new_slot_hold_id = ?, current_step = ?, last_error_code ="
            + " NULL, updated_at = ? WHERE saga_id = ?",
        holdId,
        next,
        Timestamp.from(now),
        sagaId);
  }

  public void newCapacity(String sagaId, String holdId, String next, Instant now) {
    jdbc.update(
        "UPDATE time_change_saga SET new_capacity_hold_id = ?, current_step = ?, last_error_code ="
            + " NULL, updated_at = ? WHERE saga_id = ?",
        holdId,
        next,
        Timestamp.from(now),
        sagaId);
  }

  public void lotMoved(String sagaId, boolean moved, String next, Instant now) {
    jdbc.update(
        "UPDATE time_change_saga SET lot_moved = ?, current_step = ?, updated_at = ? WHERE saga_id"
            + " = ?",
        moved,
        next,
        Timestamp.from(now),
        sagaId);
  }

  public void step(String sagaId, String step, Instant now) {
    jdbc.update(
        "UPDATE time_change_saga SET current_step = ?, last_error_code = NULL, updated_at = ? WHERE"
            + " saga_id = ?",
        step,
        Timestamp.from(now),
        sagaId);
  }

  /** 결론(CONFIRMED/REJECTED)과 다음 단계. */
  public void decide(
      String sagaId, String outcome, @Nullable String reason, String next, Instant now) {
    jdbc.update(
        "UPDATE time_change_saga SET outcome = ?, reason = ?, current_step = ?, updated_at = ? WHERE"
            + " saga_id = ?",
        outcome,
        reason,
        next,
        Timestamp.from(now),
        sagaId);
  }

  public void finish(String sagaId, Instant now) {
    jdbc.update(
        "UPDATE time_change_saga SET state = 'COMPLETED', finished_at = ?, updated_at = ? WHERE"
            + " saga_id = ?",
        Timestamp.from(now),
        Timestamp.from(now),
        sagaId);
  }

  public void error(
      String sagaId, String step, String code, String message, boolean escalate, Instant now) {
    jdbc.update(
        "UPDATE time_change_saga SET state = CASE WHEN ? THEN 'ESCALATED' ELSE state END,"
            + " last_error_step = ?, last_error_code = ?, last_error_message = ?, updated_at = ?"
            + " WHERE saga_id = ?",
        escalate,
        step,
        code,
        message.length() > 500 ? message.substring(0, 500) : message,
        Timestamp.from(now),
        sagaId);
  }

  private Row row(ResultSet rs, int n) throws SQLException {
    return new Row(
        rs.getString("saga_id"),
        rs.getString("request_id"),
        rs.getString("order_id"),
        rs.getString("mfc_id"),
        rs.getDate("old_date").toLocalDate(),
        rs.getString("old_start"),
        rs.getString("old_end"),
        rs.getDate("new_date").toLocalDate(),
        rs.getString("new_start"),
        rs.getString("new_end"),
        rs.getString("state"),
        rs.getString("current_step"),
        rs.getString("outcome"),
        rs.getString("reason"),
        rs.getString("checkout_saga_id"),
        rs.getString("reservation_id"),
        rs.getString("fulfillment_id"),
        rs.getString("old_slot_hold_id"),
        rs.getString("new_slot_hold_id"),
        rs.getString("old_capacity_hold_id"),
        rs.getString("new_capacity_hold_id"),
        rs.getBoolean("lot_moved"));
  }
}
