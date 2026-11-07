package com.pmart.mfc.capacity.infrastructure;

import com.pmart.mfc.capacity.domain.RestrictionRules.Active;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 운영 제한(V4). */
@Repository
public class RestrictionStore {

  public record Row(
      String restrictionId,
      String mfcId,
      String type,
      String state,
      String approvalRef,
      @Nullable String liftApprovalRef,
      @Nullable String reason,
      Instant effectiveFrom,
      @Nullable Instant effectiveTo,
      @Nullable Integer reducedCapacityUnits,
      String affectedOrderIds,
      String appliedBy,
      @Nullable String liftedBy,
      int version,
      Instant createdAt,
      @Nullable Instant liftedAt) {

    public Active active() {
      return new Active(restrictionId, type, effectiveFrom, effectiveTo, reducedCapacityUnits);
    }
  }

  /** 한 창의 활성 점유 주문. */
  public record WindowHold(Instant windowStart, Instant windowEnd, String orderId, int units) {}

  private final JdbcTemplate jdbc;

  public RestrictionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Row r) {
    jdbc.update(
        "INSERT INTO operation_restriction (restriction_id, mfc_id, restriction_type, state,"
            + " approval_ref, reason, effective_from, effective_to, reduced_capacity_units,"
            + " affected_order_ids, applied_by, version, created_at)"
            + " VALUES (?,?,?,'ACTIVE',?,?,?,?,?,?::jsonb,?,1,?)",
        r.restrictionId(),
        r.mfcId(),
        r.type(),
        r.approvalRef(),
        r.reason(),
        Timestamp.from(r.effectiveFrom()),
        ts(r.effectiveTo()),
        r.reducedCapacityUnits(),
        r.affectedOrderIds(),
        r.appliedBy(),
        Timestamp.from(r.createdAt()));
  }

  public Optional<Row> find(String id, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT * FROM operation_restriction WHERE restriction_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> row(rs),
            id)
        .stream()
        .findFirst();
  }

  public List<Row> list(String mfcId, boolean activeOnly) {
    return jdbc.query(
        "SELECT * FROM operation_restriction WHERE mfc_id = ?"
            + (activeOnly ? " AND state = 'ACTIVE'" : "")
            + " ORDER BY created_at DESC LIMIT 200",
        (rs, i) -> row(rs),
        mfcId);
  }

  public List<Active> active(String mfcId) {
    return jdbc.query(
        "SELECT * FROM operation_restriction WHERE mfc_id = ? AND state = 'ACTIVE'",
        (rs, i) -> row(rs).active(),
        mfcId);
  }

  public void lift(String id, String approvalRef, String by, Instant at) {
    jdbc.update(
        "UPDATE operation_restriction SET state = 'LIFTED', lift_approval_ref = ?, lifted_by = ?,"
            + " lifted_at = ?, version = version + 1 WHERE restriction_id = ? AND state = 'ACTIVE'",
        approvalRef,
        by,
        Timestamp.from(at),
        id);
  }

  public void setAffected(String id, String orderIdsJson) {
    jdbc.update(
        "UPDATE operation_restriction SET affected_order_ids = ?::jsonb WHERE restriction_id = ?",
        orderIdsJson,
        id);
  }

  /** 범위 안 창들의 활성 점유(만료된 HELD 제외). */
  public List<WindowHold> activeHolds(
      String mfcId, Instant from, @Nullable Instant to, Instant now) {
    return jdbc.query(
        "SELECT window_start, window_end, order_id, units FROM capacity_hold WHERE mfc_id = ?"
            + " AND window_end > ? AND (?::timestamptz IS NULL OR window_start < ?::timestamptz)"
            + " AND (state = 'CONFIRMED' OR (state = 'HELD' AND expires_at > ?))"
            + " ORDER BY window_start, created_at",
        (rs, i) ->
            new WindowHold(
                rs.getTimestamp("window_start").toInstant(),
                rs.getTimestamp("window_end").toInstant(),
                rs.getString("order_id"),
                rs.getInt("units")),
        mfcId,
        Timestamp.from(from),
        ts(to),
        ts(to),
        Timestamp.from(now));
  }

  private static Row row(ResultSet rs) throws SQLException {
    Object units = rs.getObject("reduced_capacity_units");
    return new Row(
        rs.getString("restriction_id"),
        rs.getString("mfc_id"),
        rs.getString("restriction_type"),
        rs.getString("state"),
        rs.getString("approval_ref"),
        rs.getString("lift_approval_ref"),
        rs.getString("reason"),
        rs.getTimestamp("effective_from").toInstant(),
        instant(rs, "effective_to"),
        units == null ? null : ((Number) units).intValue(),
        rs.getString("affected_order_ids"),
        rs.getString("applied_by"),
        rs.getString("lifted_by"),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant(),
        instant(rs, "lifted_at"));
  }

  private static @Nullable Instant instant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
