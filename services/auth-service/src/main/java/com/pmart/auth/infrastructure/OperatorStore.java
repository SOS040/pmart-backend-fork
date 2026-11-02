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

/** 운영자 계정·세션·refresh token 저장(JDBC). 비밀번호·토큰 원문은 다루지 않는다(해시만). */
@Repository
public class OperatorStore {

  public record AccountRow(
      String operatorId,
      String loginId,
      String displayName,
      String passwordHash,
      List<String> roles,
      List<String> centers,
      String state,
      int failedCount,
      @Nullable Instant lockedUntil,
      boolean passwordChangeRequired,
      @Nullable Instant lastLoginAt,
      int version,
      Instant createdAt) {}

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

  public OperatorStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  // ---------- 계정 ----------

  public void insertAccount(AccountRow a, String createdBy) {
    jdbc.update(
        "INSERT INTO operator_account (operator_id, login_id, display_name, password_hash, roles,"
            + " centers, state, failed_count, locked_until, password_change_required, last_login_at,"
            + " version, created_by, created_at, updated_at)"
            + " VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,?,?,?)",
        a.operatorId(),
        a.loginId(),
        a.displayName(),
        a.passwordHash(),
        mapper.writeValueAsString(a.roles()),
        mapper.writeValueAsString(a.centers()),
        a.state(),
        a.failedCount(),
        ts(a.lockedUntil()),
        a.passwordChangeRequired(),
        ts(a.lastLoginAt()),
        a.version(),
        createdBy,
        Timestamp.from(a.createdAt()),
        Timestamp.from(a.createdAt()));
  }

  public boolean loginIdExists(String loginId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM operator_account WHERE login_id = ?", Integer.class, loginId);
    return n != null && n > 0;
  }

  public Optional<AccountRow> byLoginId(String loginId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT * FROM operator_account WHERE login_id = ?" + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> account(rs),
            loginId)
        .stream()
        .findFirst();
  }

  public Optional<AccountRow> byId(String operatorId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT * FROM operator_account WHERE operator_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> account(rs),
            operatorId)
        .stream()
        .findFirst();
  }

  /** 목록(키셋: login_id 오름차순). */
  public List<AccountRow> list(@Nullable String afterLoginId, int limit) {
    return jdbc.query(
        "SELECT * FROM operator_account WHERE (?::text IS NULL OR login_id > ?::text)"
            + " ORDER BY login_id LIMIT ?",
        (rs, i) -> account(rs),
        afterLoginId,
        afterLoginId,
        limit);
  }

  public void recordFailure(
      String operatorId, int failedCount, @Nullable Instant lockedUntil, Instant at) {
    jdbc.update(
        "UPDATE operator_account SET failed_count = ?, locked_until = ?, updated_at = ?"
            + " WHERE operator_id = ?",
        failedCount,
        ts(lockedUntil),
        Timestamp.from(at),
        operatorId);
  }

  public void recordLogin(String operatorId, Instant at) {
    jdbc.update(
        "UPDATE operator_account SET failed_count = 0, locked_until = NULL, last_login_at = ?,"
            + " updated_at = ? WHERE operator_id = ?",
        Timestamp.from(at),
        Timestamp.from(at),
        operatorId);
  }

  public boolean updateProfile(
      String operatorId,
      int expectedVersion,
      String displayName,
      List<String> roles,
      List<String> centers,
      String state,
      Instant at) {
    return jdbc.update(
            "UPDATE operator_account SET display_name = ?, roles = ?::jsonb, centers = ?::jsonb,"
                + " state = ?, version = version + 1, updated_at = ?"
                + " WHERE operator_id = ? AND version = ?",
            displayName,
            mapper.writeValueAsString(roles),
            mapper.writeValueAsString(centers),
            state,
            Timestamp.from(at),
            operatorId,
            expectedVersion)
        == 1;
  }

  /** 비밀번호 교체. {@code changeRequired} 면 다음 로그인 때 바꾸라는 표시. 초기화는 잠금도 푼다. */
  public void setPassword(String operatorId, String hash, boolean changeRequired, Instant at) {
    jdbc.update(
        "UPDATE operator_account SET password_hash = ?, password_change_required = ?,"
            + " failed_count = 0, locked_until = NULL, version = version + 1, updated_at = ?"
            + " WHERE operator_id = ?",
        hash,
        changeRequired,
        Timestamp.from(at),
        operatorId);
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

  private AccountRow account(ResultSet rs) throws SQLException {
    return new AccountRow(
        rs.getString("operator_id"),
        rs.getString("login_id"),
        rs.getString("display_name"),
        rs.getString("password_hash"),
        mapper.readValue(rs.getString("roles"), STRINGS),
        mapper.readValue(rs.getString("centers"), STRINGS),
        rs.getString("state"),
        rs.getInt("failed_count"),
        instant(rs, "locked_until"),
        rs.getBoolean("password_change_required"),
        instant(rs, "last_login_at"),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
