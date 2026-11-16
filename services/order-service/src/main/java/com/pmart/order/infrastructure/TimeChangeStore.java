package com.pmart.order.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 예약배달 시간 변경 요청(V13 order_time_change). */
@Repository
public class TimeChangeStore {

  public record Row(
      String requestId,
      String orderId,
      String accountId,
      LocalDate fromDate,
      String fromStart,
      String fromEnd,
      LocalDate toDate,
      String toStart,
      String toEnd,
      String state,
      @Nullable String reason,
      @Nullable String sagaId,
      Instant requestedAt,
      @Nullable Instant decidedAt) {}

  private final JdbcTemplate jdbc;

  public TimeChangeStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(Row r) {
    jdbc.update(
        "INSERT INTO order_time_change (request_id, order_id, account_id, from_date, from_start,"
            + " from_end, to_date, to_start, to_end, state, requested_at) VALUES"
            + " (?,?,?,?,?,?,?,?,?,'PENDING',?)",
        r.requestId(),
        r.orderId(),
        r.accountId(),
        Date.valueOf(r.fromDate()),
        r.fromStart(),
        r.fromEnd(),
        Date.valueOf(r.toDate()),
        r.toStart(),
        r.toEnd(),
        Timestamp.from(r.requestedAt()));
  }

  public boolean pending(String orderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM order_time_change WHERE order_id = ? AND state = 'PENDING'",
            Integer.class,
            orderId);
    return n != null && n > 0;
  }

  public Optional<Row> find(String orderId, String requestId) {
    return jdbc
        .query(
            "SELECT * FROM order_time_change WHERE order_id = ? AND request_id = ?",
            this::row,
            orderId,
            requestId)
        .stream()
        .findFirst();
  }

  public void sagaStarted(String requestId, String sagaId) {
    jdbc.update("UPDATE order_time_change SET saga_id = ? WHERE request_id = ?", sagaId, requestId);
  }

  public void decide(
      String requestId,
      String state,
      @Nullable String reason,
      @Nullable String sagaId,
      Instant at) {
    jdbc.update(
        "UPDATE order_time_change SET state = ?, reason = ?, saga_id = COALESCE(?, saga_id),"
            + " decided_at = ? WHERE request_id = ? AND state = 'PENDING'",
        state,
        reason,
        sagaId,
        Timestamp.from(at),
        requestId);
  }

  private Row row(ResultSet rs, int n) throws SQLException {
    Timestamp d = rs.getTimestamp("decided_at");
    return new Row(
        rs.getString("request_id"),
        rs.getString("order_id"),
        rs.getString("account_id"),
        rs.getDate("from_date").toLocalDate(),
        rs.getString("from_start"),
        rs.getString("from_end"),
        rs.getDate("to_date").toLocalDate(),
        rs.getString("to_start"),
        rs.getString("to_end"),
        rs.getString("state"),
        rs.getString("reason"),
        rs.getString("saga_id"),
        rs.getTimestamp("requested_at").toInstant(),
        d == null ? null : d.toInstant());
  }
}
