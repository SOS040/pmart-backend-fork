package com.pmart.mfc.capacity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 작업자 휴게(작업자 쪽 3단계, V10). */
@Repository
public class BreakStore {

  public record Break(
      String breakId,
      String workerId,
      Instant startedAt,
      String startedBy,
      @Nullable Instant endedAt) {}

  private final JdbcTemplate jdbc;

  public BreakStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Break> open(String mfcId, String workerId) {
    return jdbc
        .query(
            "SELECT * FROM mfc_worker_break WHERE mfc_id = ? AND worker_id = ? AND ended_at IS NULL",
            (rs, i) -> row(rs),
            mfcId,
            workerId)
        .stream()
        .findFirst();
  }

  public Optional<Break> last(String mfcId, String workerId) {
    return jdbc
        .query(
            "SELECT * FROM mfc_worker_break WHERE mfc_id = ? AND worker_id = ? ORDER BY started_at DESC LIMIT 1",
            (rs, i) -> row(rs),
            mfcId,
            workerId)
        .stream()
        .findFirst();
  }

  /** 없으면 시작(이미 휴게 중이면 그대로). */
  public Break start(String mfcId, String workerId, String by, Instant at) {
    jdbc.update(
        "INSERT INTO mfc_worker_break (break_id, mfc_id, worker_id, started_at, started_by)"
            + " VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
        "BRK-" + UUID.randomUUID(),
        mfcId,
        workerId,
        Timestamp.from(at),
        by);
    return open(mfcId, workerId).orElseThrow();
  }

  public void end(String breakId, String by, Instant at) {
    jdbc.update(
        "UPDATE mfc_worker_break SET ended_at = ?, ended_by = ? WHERE break_id = ? AND ended_at IS NULL",
        Timestamp.from(at),
        by,
        breakId);
  }

  private static Break row(java.sql.ResultSet rs) throws java.sql.SQLException {
    Timestamp e = rs.getTimestamp("ended_at");
    return new Break(
        rs.getString("break_id"),
        rs.getString("worker_id"),
        rs.getTimestamp("started_at").toInstant(),
        rs.getString("started_by"),
        e == null ? null : e.toInstant());
  }
}
