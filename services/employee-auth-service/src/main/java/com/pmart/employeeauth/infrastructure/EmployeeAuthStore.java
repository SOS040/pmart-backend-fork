package com.pmart.employeeauth.infrastructure;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 직원·Keycloak 연결·로그인 시도·티켓·세션·refresh token 저장(JDBC). Keycloak 토큰·비밀번호·이메일은 저장하지 않는다. 역할·센터 값은 계약
 * 패턴({@code [A-Za-z0-9_.-]})만 들어오므로 배열 문자열로 넘긴다.
 */
@Repository
public class EmployeeAuthStore {

  public record AccountRow(
      String operatorId,
      String state,
      List<String> roles,
      List<String> centers,
      long version,
      Instant updatedAt,
      @Nullable Instant lastLoginAt,
      boolean keycloakLinked,
      java.time.@Nullable LocalDate startDate,
      @Nullable Long hrVersion) {}

  public record AttemptRow(
      String app,
      String returnUri,
      String deviceId,
      String ticketChallenge,
      String codeVerifier,
      String nonce) {}

  public record TicketRow(
      String ticketHash,
      String operatorId,
      String app,
      String deviceId,
      String ticketChallenge,
      Instant expiresAt,
      @Nullable Instant usedAt,
      @Nullable String returnUri,
      @Nullable String keycloakIdToken,
      @Nullable String keycloakSid) {}

  public record SessionRow(
      String sessionId,
      String operatorId,
      String deviceId,
      String app,
      String state,
      Instant maxExpiresAt,
      Instant expiresAt,
      @Nullable String returnUri,
      @Nullable String keycloakIdToken,
      @Nullable String keycloakSid) {}

  public record RefreshRow(
      String tokenHash, String sessionId, Instant expiresAt, @Nullable Instant usedAt) {}

  private final JdbcTemplate jdbc;

  public EmployeeAuthStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 직원 ----------

  public Optional<AccountRow> account(String operatorId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT a.*, EXISTS (SELECT 1 FROM employee_identity i WHERE i.operator_id = a.operator_id) AS linked"
                + " FROM employee_account a WHERE a.operator_id = ?"
                + (forUpdate ? " FOR UPDATE OF a" : ""),
            (rs, i) -> account(rs),
            operatorId)
        .stream()
        .findFirst();
  }

  public void insertAccount(
      String operatorId, String state, List<String> roles, List<String> centers, Instant at) {
    jdbc.update(
        "INSERT INTO employee_account (operator_id, state, roles, centers, row_version, created_at, updated_at)"
            + " VALUES (?, ?, ?::varchar[], ?::varchar[], 1, ?, ?)",
        operatorId,
        state,
        array(roles),
        array(centers),
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public void updateAccount(
      String operatorId, String state, List<String> roles, List<String> centers, Instant at) {
    jdbc.update(
        "UPDATE employee_account SET state = ?, roles = ?::varchar[], centers = ?::varchar[],"
            + " row_version = row_version + 1, updated_at = ? WHERE operator_id = ?",
        state,
        array(roles),
        array(centers),
        Timestamp.from(at),
        operatorId);
  }

  public void insertChange(
      String operatorId,
      String changedBy,
      String sourceSystem,
      String reason,
      @Nullable AccountRow before,
      String state,
      List<String> roles,
      List<String> centers,
      Instant at) {
    jdbc.update(
        "INSERT INTO employee_account_change (operator_id, changed_by, source_system, reason,"
            + " before_state, before_roles, before_centers, after_state, after_roles, after_centers, changed_at)"
            + " VALUES (?, ?, ?, ?, ?, ?::varchar[], ?::varchar[], ?, ?::varchar[], ?::varchar[], ?)",
        operatorId,
        changedBy,
        sourceSystem,
        reason,
        before == null ? null : before.state(),
        before == null ? null : array(before.roles()),
        before == null ? null : array(before.centers()),
        state,
        array(roles),
        array(centers),
        Timestamp.from(at));
  }

  /**
   * hr-service 직원 사건 반영 — 없으면 만들고(역할·센터 없음 — 권한은 approval 승인 뒤), 있으면 상태·근무 시작일·사건 버전만 바꾼다(역할·센터는
   * 그대로 — 이전한 운영자의 권한을 지우지 않는다).
   */
  public void applyHr(
      String operatorId,
      String state,
      java.time.@Nullable LocalDate startDate,
      long hrVersion,
      Instant at) {
    int updated =
        jdbc.update(
            "UPDATE employee_account SET state = ?, start_date = COALESCE(?, start_date), hr_version = ?,"
                + " row_version = row_version + 1, updated_at = ? WHERE operator_id = ?",
            state,
            startDate == null ? null : java.sql.Date.valueOf(startDate),
            hrVersion,
            Timestamp.from(at),
            operatorId);
    if (updated == 0) {
      jdbc.update(
          "INSERT INTO employee_account (operator_id, state, roles, centers, row_version, created_at, updated_at,"
              + " start_date, hr_version) VALUES (?, ?, '{}', '{}', 1, ?, ?, ?, ?)",
          operatorId,
          state,
          Timestamp.from(at),
          Timestamp.from(at),
          startDate == null ? null : java.sql.Date.valueOf(startDate),
          hrVersion);
    }
  }

  /**
   * hr-service 가 알려 준 Keycloak 사용자로 연결한다(hr-service 가 정본 — 그 운영자 ID 의 예전 연결은 바꾼다). 그 Keycloak 사용자가
   * 이미 다른 운영자 ID 에 연결돼 있으면 바꾸지 않고 false.
   */
  public boolean relink(String keycloakSubject, String operatorId, Instant at) {
    Optional<String> owner = linkedOperator(keycloakSubject);
    if (owner.isPresent()) {
      return owner.get().equals(operatorId);
    }
    jdbc.update("DELETE FROM employee_identity WHERE operator_id = ?", operatorId);
    return link(keycloakSubject, operatorId, at) == 1;
  }

  // ---------- Keycloak 연결 ----------

  public Optional<String> linkedOperator(String keycloakSubject) {
    return jdbc
        .queryForList(
            "SELECT operator_id FROM employee_identity WHERE keycloak_subject = ?",
            String.class,
            keycloakSubject)
        .stream()
        .findFirst();
  }

  public Optional<String> linkedSubject(String operatorId) {
    return jdbc
        .queryForList(
            "SELECT keycloak_subject FROM employee_identity WHERE operator_id = ?",
            String.class,
            operatorId)
        .stream()
        .findFirst();
  }

  /** 연결. 이미 있으면 0(동시 로그인 — 호출자가 다시 읽는다). */
  public int link(String keycloakSubject, String operatorId, Instant at) {
    return jdbc.update(
        "INSERT INTO employee_identity (keycloak_subject, operator_id, linked_at) VALUES (?, ?, ?)"
            + " ON CONFLICT DO NOTHING",
        keycloakSubject,
        operatorId,
        Timestamp.from(at));
  }

  public void recordLogin(String keycloakSubject, String operatorId, Instant at) {
    jdbc.update(
        "UPDATE employee_identity SET last_login_at = ? WHERE keycloak_subject = ?",
        Timestamp.from(at),
        keycloakSubject);
    jdbc.update(
        "UPDATE employee_account SET last_login_at = ? WHERE operator_id = ?",
        Timestamp.from(at),
        operatorId);
  }

  // ---------- 로그인 시도 ----------

  public void insertAttempt(
      String stateHash,
      String app,
      String returnUri,
      String deviceId,
      String ticketChallenge,
      String codeVerifier,
      String nonce,
      Instant at,
      Instant expiresAt) {
    jdbc.update(
        "INSERT INTO login_attempt (state_hash, app, return_uri, device_id, ticket_challenge, code_verifier,"
            + " nonce, created_at, expires_at) VALUES (?,?,?,?,?,?,?,?,?)",
        stateHash,
        app,
        returnUri,
        deviceId,
        ticketChallenge,
        codeVerifier,
        nonce,
        Timestamp.from(at),
        Timestamp.from(expiresAt));
  }

  /** 아직 쓰지 않았고 만료 전인 시도를 한 번만 꺼낸다(동시에 두 번 오면 한쪽만). verifier 는 지운다. */
  public Optional<AttemptRow> consumeAttempt(String stateHash, Instant now) {
    return jdbc
        .query(
            "WITH c AS (SELECT * FROM login_attempt WHERE state_hash = ? AND consumed_at IS NULL"
                + " AND expires_at > ? FOR UPDATE)"
                + " UPDATE login_attempt a SET consumed_at = ?, code_verifier = NULL FROM c"
                + " WHERE a.state_hash = c.state_hash"
                + " RETURNING c.app, c.return_uri, c.device_id, c.ticket_challenge, c.code_verifier, c.nonce",
            (rs, i) ->
                new AttemptRow(
                    rs.getString("app"),
                    rs.getString("return_uri"),
                    rs.getString("device_id"),
                    rs.getString("ticket_challenge"),
                    rs.getString("code_verifier"),
                    rs.getString("nonce")),
            stateHash,
            Timestamp.from(now),
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  // ---------- 티켓 ----------

  public void insertTicket(
      String ticketHash,
      String operatorId,
      String app,
      String deviceId,
      String ticketChallenge,
      Instant at,
      Instant expiresAt,
      String returnUri,
      @Nullable String keycloakIdToken) {
    insertTicket(
        ticketHash,
        operatorId,
        app,
        deviceId,
        ticketChallenge,
        at,
        expiresAt,
        returnUri,
        keycloakIdToken,
        null);
  }

  /** {@code keycloakSid}: SSO 앱만 — 같은 Keycloak 세션의 PMart 세션을 함께 끝내려고(ADR-0061). */
  public void insertTicket(
      String ticketHash,
      String operatorId,
      String app,
      String deviceId,
      String ticketChallenge,
      Instant at,
      Instant expiresAt,
      String returnUri,
      @Nullable String keycloakIdToken,
      @Nullable String keycloakSid) {
    jdbc.update(
        "INSERT INTO login_ticket (ticket_hash, operator_id, app, device_id, ticket_challenge, issued_at, expires_at,"
            + " return_uri, keycloak_id_token, keycloak_sid) VALUES (?,?,?,?,?,?,?,?,?,?)",
        ticketHash,
        operatorId,
        app,
        deviceId,
        ticketChallenge,
        Timestamp.from(at),
        Timestamp.from(expiresAt),
        returnUri,
        keycloakIdToken,
        keycloakSid);
  }

  public Optional<TicketRow> lockTicket(String ticketHash) {
    return jdbc
        .query(
            "SELECT * FROM login_ticket WHERE ticket_hash = ? FOR UPDATE",
            (rs, i) ->
                new TicketRow(
                    rs.getString("ticket_hash"),
                    rs.getString("operator_id"),
                    rs.getString("app"),
                    rs.getString("device_id"),
                    rs.getString("ticket_challenge"),
                    instant(rs, "expires_at"),
                    nullableInstant(rs, "used_at"),
                    rs.getString("return_uri"),
                    rs.getString("keycloak_id_token"),
                    rs.getString("keycloak_sid")),
            ticketHash)
        .stream()
        .findFirst();
  }

  public void markTicketUsed(String ticketHash, Instant at) {
    jdbc.update(
        "UPDATE login_ticket SET used_at = ?, keycloak_id_token = NULL WHERE ticket_hash = ? AND used_at IS NULL",
        Timestamp.from(at),
        ticketHash);
  }

  /** 하루 지난 시도·티켓을 지운다(감사 기록이 아니다 — 세션·변경 기록은 남는다). */
  public int purgeExpired(Instant before) {
    // 끝난(만료된) SSO 세션의 Keycloak ID 토큰도 지운다 — 로그아웃 때만 쓰고, 세션이 끝나면 쓸 데가 없다
    jdbc.update(
        "UPDATE employee_session SET keycloak_id_token = NULL"
            + " WHERE keycloak_id_token IS NOT NULL AND expires_at < ?",
        Timestamp.from(before));
    return jdbc.update("DELETE FROM login_attempt WHERE expires_at < ?", Timestamp.from(before))
        + jdbc.update("DELETE FROM login_ticket WHERE expires_at < ?", Timestamp.from(before));
  }

  // ---------- 세션·refresh ----------

  public void insertSession(
      String sessionId,
      String operatorId,
      String deviceId,
      String app,
      String sourceSystem,
      Instant at,
      Instant maxExpiresAt,
      Instant expiresAt,
      @Nullable String returnUri,
      @Nullable String keycloakIdToken) {
    insertSession(
        sessionId,
        operatorId,
        deviceId,
        app,
        sourceSystem,
        at,
        maxExpiresAt,
        expiresAt,
        returnUri,
        keycloakIdToken,
        null);
  }

  public void insertSession(
      String sessionId,
      String operatorId,
      String deviceId,
      String app,
      String sourceSystem,
      Instant at,
      Instant maxExpiresAt,
      Instant expiresAt,
      @Nullable String returnUri,
      @Nullable String keycloakIdToken,
      @Nullable String keycloakSid) {
    jdbc.update(
        "INSERT INTO employee_session (session_id, operator_id, device_id, app, source_system, state,"
            + " created_at, max_expires_at, expires_at, return_uri, keycloak_id_token, keycloak_sid)"
            + " VALUES (?,?,?,?,?,'ACTIVE',?,?,?,?,?,?)",
        sessionId,
        operatorId,
        deviceId,
        app,
        sourceSystem,
        Timestamp.from(at),
        Timestamp.from(maxExpiresAt),
        Timestamp.from(expiresAt),
        returnUri,
        keycloakIdToken,
        keycloakSid);
  }

  public Optional<SessionRow> session(String sessionId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT * FROM employee_session WHERE session_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            EmployeeAuthStore::sessionRow,
            sessionId)
        .stream()
        .findFirst();
  }

  private static SessionRow sessionRow(ResultSet rs, int i) throws SQLException {
    return new SessionRow(
        rs.getString("session_id"),
        rs.getString("operator_id"),
        rs.getString("device_id"),
        rs.getString("app"),
        rs.getString("state"),
        instant(rs, "max_expires_at"),
        instant(rs, "expires_at"),
        rs.getString("return_uri"),
        rs.getString("keycloak_id_token"),
        rs.getString("keycloak_sid"));
  }

  /** 같은 Keycloak 세션으로 열린 살아 있는 세션(ADR-0061). 잠근다. */
  public List<SessionRow> activeSessionsByKeycloakSid(String keycloakSid) {
    return jdbc.query(
        "SELECT * FROM employee_session WHERE keycloak_sid = ? AND state = 'ACTIVE'"
            + " ORDER BY session_id FOR UPDATE",
        EmployeeAuthStore::sessionRow,
        keycloakSid);
  }

  /** 그 직원의 살아 있는 SSO 앱 세션(Keycloak 세션에 묶인 것, ADR-0061 — sid 없는 백채널 로그아웃). 잠근다. */
  public List<SessionRow> activeSsoSessions(String operatorId) {
    return jdbc.query(
        "SELECT * FROM employee_session WHERE operator_id = ? AND state = 'ACTIVE'"
            + " AND keycloak_sid IS NOT NULL ORDER BY session_id FOR UPDATE",
        EmployeeAuthStore::sessionRow,
        operatorId);
  }

  public void refreshed(String sessionId, Instant at, Instant expiresAt) {
    jdbc.update(
        "UPDATE employee_session SET last_refreshed_at = ?, expires_at = ? WHERE session_id = ?",
        Timestamp.from(at),
        Timestamp.from(expiresAt),
        sessionId);
  }

  public void revokeSession(String sessionId, String reason, Instant at) {
    jdbc.update(
        "UPDATE employee_session SET state = 'REVOKED', revoked_at = ?, revoke_reason = ?,"
            + " keycloak_id_token = NULL"
            + " WHERE session_id = ? AND state = 'ACTIVE'",
        Timestamp.from(at),
        reason,
        sessionId);
  }

  public int revokeAllSessions(String operatorId, String reason, Instant at) {
    return jdbc.update(
        "UPDATE employee_session SET state = 'REVOKED', revoked_at = ?, revoke_reason = ?,"
            + " keycloak_id_token = NULL"
            + " WHERE operator_id = ? AND state = 'ACTIVE'",
        Timestamp.from(at),
        reason,
        operatorId);
  }

  public void insertRefresh(String tokenHash, String sessionId, Instant at, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO employee_refresh_token (token_hash, session_id, issued_at, expires_at) VALUES (?,?,?,?)",
        tokenHash,
        sessionId,
        Timestamp.from(at),
        Timestamp.from(expiresAt));
  }

  public Optional<RefreshRow> lockRefresh(String tokenHash) {
    return jdbc
        .query(
            "SELECT * FROM employee_refresh_token WHERE token_hash = ? FOR UPDATE",
            (rs, i) ->
                new RefreshRow(
                    rs.getString("token_hash"),
                    rs.getString("session_id"),
                    instant(rs, "expires_at"),
                    nullableInstant(rs, "used_at")),
            tokenHash)
        .stream()
        .findFirst();
  }

  public void markRefreshUsed(String tokenHash, Instant at) {
    jdbc.update(
        "UPDATE employee_refresh_token SET used_at = ? WHERE token_hash = ?",
        Timestamp.from(at),
        tokenHash);
  }

  // ---------- 승인된 권한(ADR-0056 5-4) ----------

  /** 지금 유효한 grant 의 역할·센터. */
  public record GrantAccess(String role, List<String> centers) {}

  /** 승인 행 하나를 grant 로. 같은 승인·행이 이미 있으면 false(재전달). */
  public boolean insertGrant(
      String grantId,
      String operatorId,
      String role,
      List<String> centers,
      Instant validFrom,
      @Nullable Instant validUntil,
      String approvalId,
      String lineId,
      String approverId,
      @Nullable String reason,
      Instant at) {
    return jdbc.update(
            "INSERT INTO employee_grant (grant_id, operator_id, role, centers, valid_from, valid_until,"
                + " approval_id, line_id, approver_id, reason, applied_at)"
                + " VALUES (?, ?, ?, ?::varchar[], ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT DO NOTHING",
            grantId,
            operatorId,
            role,
            array(centers),
            Timestamp.from(validFrom),
            validUntil == null ? null : Timestamp.from(validUntil),
            approvalId,
            lineId,
            approverId,
            reason,
            Timestamp.from(at))
        == 1;
  }

  public List<GrantAccess> activeGrants(String operatorId, Instant now) {
    Timestamp t = Timestamp.from(now);
    return jdbc.query(
        "SELECT role, centers FROM employee_grant WHERE operator_id = ? AND valid_from <= ?"
            + " AND (valid_until IS NULL OR valid_until > ?) ORDER BY valid_from, grant_id",
        (rs, i) -> new GrantAccess(rs.getString("role"), list(rs.getArray("centers"))),
        operatorId,
        t,
        t);
  }

  // ---------- 변환 ----------

  private static AccountRow account(ResultSet rs) throws SQLException {
    return new AccountRow(
        rs.getString("operator_id"),
        rs.getString("state"),
        list(rs.getArray("roles")),
        list(rs.getArray("centers")),
        rs.getLong("row_version"),
        instant(rs, "updated_at"),
        nullableInstant(rs, "last_login_at"),
        rs.getBoolean("linked"),
        rs.getDate("start_date") == null ? null : rs.getDate("start_date").toLocalDate(),
        rs.getObject("hr_version") == null ? null : rs.getLong("hr_version"));
  }

  static String array(List<String> values) {
    return "{" + String.join(",", values) + "}";
  }

  private static List<String> list(@Nullable Array a) throws SQLException {
    if (a == null) {
      return List.of();
    }
    return Arrays.stream((Object[]) a.getArray()).map(String::valueOf).toList();
  }

  private static Instant instant(ResultSet rs, String col) throws SQLException {
    return rs.getTimestamp(col).toInstant();
  }

  private static @Nullable Instant nullableInstant(ResultSet rs, String col) throws SQLException {
    Timestamp t = rs.getTimestamp(col);
    return t == null ? null : t.toInstant();
  }
}
