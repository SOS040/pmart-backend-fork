package com.pmart.mfc.inventory.infrastructure;

import com.pmart.mfc.inventory.domain.ReservationState;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 예약 저장(JDBC): 머리 {@code reservation} + 조각 {@code reservation_part}(LOT 단위, ADR-0040 결정 4). 상태 전이는
 * 호출자가 {@link #lock} 으로 예약 행을 잠근 뒤에 한다. 잠금 순서는 예약 행 → 센터 요약(SKU 오름차순) → LOT 요약.
 */
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
      @Nullable LocalDate promisedDate,
      Instant createdAt,
      Instant updatedAt) {}

  /** 예약 조각. */
  public record PartRow(
      String partId,
      @Nullable String orderLineId,
      String sku,
      String lotId,
      String kind,
      int qty,
      ReservationState state,
      @Nullable String blockedReason) {}

  private static final String COLUMNS =
      "reservation_id, order_id, mfc_id, state, expires_at, release_reason, row_version,"
          + " promised_date, created_at, updated_at";

  private final JdbcTemplate jdbc;

  public ReservationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(
      String reservationId,
      String orderId,
      String mfcId,
      Instant expiresAt,
      @Nullable LocalDate promisedDate,
      String sourceSystem,
      String requestId,
      Instant at) {
    jdbc.update(
        "INSERT INTO reservation (reservation_id, order_id, mfc_id, state, expires_at, row_version,"
            + " promised_date, source_system, request_id, created_at, updated_at)"
            + " VALUES (?,?,?,'HELD',?,1,?,?,?,?,?)",
        reservationId,
        orderId,
        mfcId,
        Timestamp.from(expiresAt),
        promisedDate == null ? null : Date.valueOf(promisedDate),
        sourceSystem,
        requestId,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public void insertPart(String reservationId, PartRow p, Instant at) {
    jdbc.update(
        "INSERT INTO reservation_part (part_id, reservation_id, order_line_id, sku, lot_id, kind,"
            + " qty, state, blocked_reason, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        p.partId(),
        reservationId,
        p.orderLineId(),
        p.sku(),
        p.lotId(),
        p.kind(),
        p.qty(),
        p.state().name(),
        p.blockedReason(),
        Timestamp.from(at),
        Timestamp.from(at));
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

  /** 조각(SKU·LOT 순). */
  public List<PartRow> parts(String reservationId) {
    return jdbc.query(
        "SELECT part_id, order_line_id, sku, lot_id, kind, qty, state, blocked_reason"
            + " FROM reservation_part WHERE reservation_id = ? ORDER BY sku, lot_id, part_id",
        (rs, i) ->
            new PartRow(
                rs.getString("part_id"),
                rs.getString("order_line_id"),
                rs.getString("sku"),
                rs.getString("lot_id"),
                rs.getString("kind"),
                rs.getInt("qty"),
                ReservationState.valueOf(rs.getString("state")),
                rs.getString("blocked_reason")),
        reservationId);
  }

  /** SKU 오름차순 SKU → 현물 점유 수량(HELD+CONFIRMED 조각 합). */
  public Map<String, Integer> heldBySku(String reservationId) {
    Map<String, Integer> out = new TreeMap<>();
    for (PartRow p : parts(reservationId)) {
      if (p.state().holdsStock()) {
        out.merge(p.sku(), p.qty(), Integer::sum);
      }
    }
    return out;
  }

  /** 머리 상태 전이(잠근 행). 확정·해제·만료 뒤에는 만료 시각을 지운다. */
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

  /** 조각 상태 전이: from 상태인 조각 전부를 to 로. */
  public int transitionParts(
      String reservationId, ReservationState from, ReservationState to, Instant at) {
    return jdbc.update(
        "UPDATE reservation_part SET state = ?, updated_at = ? WHERE reservation_id = ?"
            + " AND state = ?",
        to.name(),
        Timestamp.from(at),
        reservationId,
        from.name());
  }

  private static ReservationRow row(ResultSet rs) throws SQLException {
    Timestamp exp = rs.getTimestamp("expires_at");
    Date pd = rs.getDate("promised_date");
    return new ReservationRow(
        rs.getString("reservation_id"),
        rs.getString("order_id"),
        rs.getString("mfc_id"),
        ReservationState.valueOf(rs.getString("state")),
        exp == null ? null : exp.toInstant(),
        rs.getString("release_reason"),
        rs.getInt("row_version"),
        pd == null ? null : pd.toLocalDate(),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
