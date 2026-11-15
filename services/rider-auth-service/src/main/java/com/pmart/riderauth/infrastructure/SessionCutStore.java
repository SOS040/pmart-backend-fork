package com.pmart.riderauth.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 정지·계약 종료 라이더 로그인 끊기 사본(V2 {@code rider_session_cut}, 2026-11-15). */
@Repository
public class SessionCutStore {

  public record CutRow(
      String riderId,
      String restriction,
      @Nullable Instant restrictionUntil,
      long riderVersion,
      String state,
      Instant requestedAt,
      @Nullable Instant cutAt) {}

  private static final String COLUMNS =
      "rider_id, restriction, restriction_until, rider_version, state, requested_at, cut_at";

  private final JdbcTemplate jdbc;

  public SessionCutStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<CutRow> lock(String riderId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM rider_session_cut WHERE rider_id = ? FOR UPDATE",
            this::map,
            riderId)
        .stream()
        .findFirst();
  }

  public Optional<CutRow> find(String riderId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM rider_session_cut WHERE rider_id = ?", this::map, riderId)
        .stream()
        .findFirst();
  }

  /** 새 사본 또는 덮어쓰기(호출자가 잠금·버전 비교를 끝낸 뒤). */
  public void upsert(
      String riderId,
      String restriction,
      @Nullable Instant until,
      long version,
      String state,
      Instant requestedAt,
      @Nullable Instant cutAt) {
    jdbc.update(
        "INSERT INTO rider_session_cut (rider_id, restriction, restriction_until, rider_version,"
            + " state, requested_at, cut_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (rider_id) DO UPDATE SET restriction = EXCLUDED.restriction,"
            + " restriction_until = EXCLUDED.restriction_until,"
            + " rider_version = EXCLUDED.rider_version, state = EXCLUDED.state,"
            + " requested_at = EXCLUDED.requested_at, cut_at = EXCLUDED.cut_at",
        riderId,
        restriction,
        ts(until),
        version,
        state,
        ts(requestedAt),
        ts(cutAt));
  }

  /** 기다리는 라이더(오래된 것 먼저). */
  public List<String> pending(int limit) {
    return jdbc.queryForList(
        "SELECT rider_id FROM rider_session_cut WHERE state = 'PENDING' ORDER BY requested_at LIMIT ?",
        String.class,
        limit);
  }

  public void checked(String riderId, Instant at) {
    jdbc.update(
        "UPDATE rider_session_cut SET last_checked_at = ? WHERE rider_id = ?", ts(at), riderId);
  }

  public void state(String riderId, String state, @Nullable Instant cutAt) {
    jdbc.update(
        "UPDATE rider_session_cut SET state = ?, cut_at = ? WHERE rider_id = ?",
        state,
        ts(cutAt),
        riderId);
  }

  private CutRow map(ResultSet rs, int i) throws SQLException {
    return new CutRow(
        rs.getString(1),
        rs.getString(2),
        instant(rs.getTimestamp(3)),
        rs.getLong(4),
        rs.getString(5),
        rs.getTimestamp(6).toInstant(),
        instant(rs.getTimestamp(7)));
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
