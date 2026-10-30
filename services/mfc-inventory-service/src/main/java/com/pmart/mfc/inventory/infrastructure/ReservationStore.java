package com.pmart.mfc.inventory.infrastructure;

import com.pmart.mfc.inventory.domain.ReservationState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 예약 저장(JDBC). 상태 전이는 호출자가 {@link #lock} 으로 예약 행을 잠근 뒤에 한다. 잠금 순서는 예약 행 → 잔량 행(SKU 오름차순). */
@Repository
public class ReservationStore {

  /** 예약 머리. */
  public record ReservationRow(
      String reservationId,
      String orderId,
      String mfcId,
      ReservationState state,
      @Nullable Instant expiresAt,
      @Nullable String releaseReason,
      int version,
      Instant createdAt,
      Instant updatedAt) {}

  private static final String COLUMNS =
      "reservation_id, order_id, mfc_id, state, expires_at, release_reason, row_version,"
          + " created_at, updated_at";

  private final JdbcTemplate jdbc;

  public ReservationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(
      String reservationId,
      String orderId,
      String mfcId,
      Instant expiresAt,
      String sourceSystem,
      String requestId,
      Instant at,
      Map<String, Integer> lines) {
    jdbc.update(
        "INSERT INTO reservation (reservation_id, order_id, mfc_id, state, expires_at, row_version,"
            + " source_system, request_id, created_at, updated_at)"
            + " VALUES (?,?,?,'HELD',?,1,?,?,?,?)",
        reservationId,
        orderId,
        mfcId,
        Timestamp.from(expiresAt),
        sourceSystem,
        requestId,
        Timestamp.from(at),
        Timestamp.from(at));
    lines.forEach(
        (sku, qty) ->
            jdbc.update(
                "INSERT INTO reservation_line (reservation_id, sku, qty) VALUES (?,?,?)",
                reservationId,
                sku,
                qty));
  }

  public Optional<ReservationRow> find(String reservationId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM reservation WHERE reservation_id = ?",
            (rs, i) -> row(rs),
            reservationId)
        .stream()
        .findFirst();
  }

  /** 예약 행을 잠그고 읽는다. */
  public Optional<ReservationRow> lock(String reservationId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM reservation WHERE reservation_id = ? FOR UPDATE",
            (rs, i) -> row(rs),
            reservationId)
        .stream()
        .findFirst();
  }

  /**
   * 만료 시각이 지난 HELD 예약을 잠근다. 다른 인스턴스·요청이 잡고 있는 행은 건너뛴다(SKIP LOCKED) — 확정·해제 요청과 겹치면 그쪽이 먼저 끝내고, 다음
   * 주기에 다시 본다.
   */
  public List<ReservationRow> lockExpired(Instant now, int limit) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM reservation WHERE state = 'HELD' AND expires_at <= ?"
            + " ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED",
        (rs, i) -> row(rs),
        Timestamp.from(now),
        limit);
  }

  /** SKU 오름차순 SKU → 수량. */
  public Map<String, Integer> lines(String reservationId) {
    Map<String, Integer> out = new TreeMap<>();
    jdbc.query(
        "SELECT sku, qty FROM reservation_line WHERE reservation_id = ? ORDER BY sku",
        rs -> {
          out.put(rs.getString("sku"), rs.getInt("qty"));
        },
        reservationId);
    return out;
  }

  /** 상태 전이(잠근 행). 확정·해제·만료 뒤에는 만료 시각을 지운다. */
  public void transition(
      String reservationId,
      ReservationState to,
      @Nullable String releaseReason,
      int newVersion,
      Instant at) {
    jdbc.update(
        "UPDATE reservation SET state = ?, expires_at = NULL, release_reason = ?, row_version = ?,"
            + " updated_at = ? WHERE reservation_id = ?",
        to.name(),
        releaseReason,
        newVersion,
        Timestamp.from(at),
        reservationId);
  }

  private static ReservationRow row(ResultSet rs) throws SQLException {
    Timestamp exp = rs.getTimestamp("expires_at");
    return new ReservationRow(
        rs.getString("reservation_id"),
        rs.getString("order_id"),
        rs.getString("mfc_id"),
        ReservationState.valueOf(rs.getString("state")),
        exp == null ? null : exp.toInstant(),
        rs.getString("release_reason"),
        rs.getInt("row_version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
