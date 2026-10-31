package com.pmart.mfc.fulfillment.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 주문 단위 작업 중지 기록(JDBC). */
@Repository
public class WorkStopStore {

  public record StopRow(
      String stopRequestId,
      String orderId,
      @Nullable String claimId,
      String scope,
      String requestedLines,
      String state,
      String notStartedLines,
      Instant decidedAt) {}

  private final JdbcTemplate jdbc;

  public WorkStopStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<StopRow> find(String stopRequestId) {
    return jdbc
        .query(
            "SELECT * FROM order_work_stop WHERE stop_request_id = ?",
            (rs, i) -> row(rs),
            stopRequestId)
        .stream()
        .findFirst();
  }

  public void insert(StopRow r) {
    jdbc.update(
        "INSERT INTO order_work_stop (stop_request_id, order_id, claim_id, scope, requested_lines,"
            + " state, not_started_lines, decided_at) VALUES (?,?,?,?,?::jsonb,?,?::jsonb,?)",
        r.stopRequestId(),
        r.orderId(),
        r.claimId(),
        r.scope(),
        r.requestedLines(),
        r.state(),
        r.notStartedLines(),
        Timestamp.from(r.decidedAt()));
  }

  private static StopRow row(ResultSet rs) throws SQLException {
    return new StopRow(
        rs.getString("stop_request_id"),
        rs.getString("order_id"),
        rs.getString("claim_id"),
        rs.getString("scope"),
        rs.getString("requested_lines"),
        rs.getString("state"),
        rs.getString("not_started_lines"),
        rs.getTimestamp("decided_at").toInstant());
  }
}
