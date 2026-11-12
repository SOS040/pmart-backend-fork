package com.pmart.auth.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그인 세션·refresh token 저장(JDBC, 고객·비회원). 토큰 원문은 다루지 않는다(해시만). 운영자 계정(operator_account)은 2026-11-13
 * 없앴다(ADR-0056 7단계 — 직원은 employee-auth-service) — 표는 남아 있고 쓰지 않는다.
 */
@Repository
public class SessionStore {

  public record SessionRow(
      String sessionId,
      String principalId,
      String principalType,
      @Nullable String deviceId,
      String state,
      Instant createdAt,
      Instant expiresAt) {}

  public record RefreshRow(
      String tokenHash, String sessionId, Instant expiresAt, @Nullable Instant usedAt) {}

  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public SessionStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  // ---------- 세션·refresh token ----------

  public void insertSession(
      String sessionId,
      String principalId,
      String principalType,
      @Nullable String deviceId,
      String sourceSystem,
      Instant at,
      Instant expiresAt) {
    jdbc.update(
        "INSERT INTO auth_session (session_id, principal_id, principal_type, device_id,"
            + " source_system, state, created_at, expires_at) VALUES (?,?,?,?,?,'ACTIVE',?,?)",
        sessionId,
        principalId,
        principalType,
        deviceId,
        sourceSystem,
        Timestamp.from(at),
        Timestamp.from(expiresAt));
  }

  public Optional<SessionRow> session(String sessionId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT session_id, principal_id, principal_type, device_id, state, created_at,"
                + " expires_at FROM auth_session WHERE session_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) ->
                new SessionRow(
                    rs.getString("session_id"),
                    rs.getString("principal_id"),
                    rs.getString("principal_type"),
                    rs.getString("device_id"),
                    rs.getString("state"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant()),
            sessionId)
        .stream()
        .findFirst();
  }

  public void revokeSession(String sessionId, String reason, Instant at) {
    jdbc.update(
        "UPDATE auth_session SET state = 'REVOKED', revoked_at = ?, revoke_reason = ?"
            + " WHERE session_id = ? AND state = 'ACTIVE'",
        Timestamp.from(at),
        reason,
        sessionId);
  }

  public void revokeAllSessions(String principalId, String reason, Instant at) {
    jdbc.update(
        "UPDATE auth_session SET state = 'REVOKED', revoked_at = ?, revoke_reason = ?"
            + " WHERE principal_id = ? AND state = 'ACTIVE'",
        Timestamp.from(at),
        reason,
        principalId);
  }

  /** 세션 만료 시각을 늦춘다(고객 세션 — 마지막 사용 기준 연장, 2026-11-02). 앞당기지는 않는다. */
  public void extendSession(String sessionId, Instant expiresAt) {
    jdbc.update(
        "UPDATE auth_session SET expires_at = ? WHERE session_id = ? AND expires_at < ?",
        Timestamp.from(expiresAt),
        sessionId,
        Timestamp.from(expiresAt));
  }

  public void markRefreshed(String sessionId, Instant at) {
    jdbc.update(
        "UPDATE auth_session SET last_refreshed_at = ? WHERE session_id = ?",
        Timestamp.from(at),
        sessionId);
  }

  public void insertRefresh(String tokenHash, String sessionId, Instant at, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO refresh_token (token_hash, session_id, issued_at, expires_at) VALUES (?,?,?,?)",
        tokenHash,
        sessionId,
        Timestamp.from(at),
        Timestamp.from(expiresAt));
  }

  /** 같은 토큰이 동시에 오면 한쪽만 통과하도록 행을 잠근다. */
  public Optional<RefreshRow> lockRefresh(String tokenHash) {
    return jdbc
        .query(
            "SELECT token_hash, session_id, expires_at, used_at FROM refresh_token"
                + " WHERE token_hash = ? FOR UPDATE",
            (rs, i) ->
                new RefreshRow(
                    rs.getString("token_hash"),
                    rs.getString("session_id"),
                    rs.getTimestamp("expires_at").toInstant(),
                    instant(rs, "used_at")),
            tokenHash)
        .stream()
        .findFirst();
  }

  public void markRefreshUsed(String tokenHash, Instant at) {
    jdbc.update(
        "UPDATE refresh_token SET used_at = ? WHERE token_hash = ?", Timestamp.from(at), tokenHash);
  }

  private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
