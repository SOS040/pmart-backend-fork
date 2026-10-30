package com.pmart.auth.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 고객 계정·제공자 연결·원인증 요청·기기 제한 저장(JDBC). 제공자 토큰·이메일은 저장하지 않는다. */
@Repository
public class CustomerStore {

  public record AuthRequestRow(
      String authRequestId,
      String deviceId,
      String provider,
      String state,
      @Nullable String failureCode,
      @Nullable String accountId,
      boolean newAccount,
      Instant createdAt) {}

  private final JdbcTemplate jdbc;

  public CustomerStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 계정·연결 ----------

  public Optional<String> accountState(String accountId) {
    return jdbc
        .queryForList(
            "SELECT state FROM customer_account WHERE account_id = ?", String.class, accountId)
        .stream()
        .findFirst();
  }

  /** 제공자 계정에 연결된 내부 계정(행 잠금 — 첫 가입 경합 방지는 insertIdentity 의 기본 키가 맡는다). */
  public Optional<String> linkedAccount(String provider, String subject) {
    return jdbc
        .queryForList(
            "SELECT account_id FROM social_identity WHERE provider = ? AND provider_subject = ?",
            String.class,
            provider,
            subject)
        .stream()
        .findFirst();
  }

  public void insertAccount(String accountId, Instant at) {
    jdbc.update(
        "INSERT INTO customer_account (account_id, state, created_at, last_login_at)"
            + " VALUES (?, 'ACTIVE', ?, ?)",
        accountId,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  /** 연결 생성. 같은 제공자 계정이 동시에 들어오면 한쪽은 0(이미 연결됨). */
  public int insertIdentity(String provider, String subject, String accountId, Instant at) {
    return jdbc.update(
        "INSERT INTO social_identity (provider, provider_subject, account_id, linked_at, last_login_at)"
            + " VALUES (?,?,?,?,?) ON CONFLICT (provider, provider_subject) DO NOTHING",
        provider,
        subject,
        accountId,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public void recordLogin(String provider, String subject, String accountId, Instant at) {
    jdbc.update(
        "UPDATE social_identity SET last_login_at = ? WHERE provider = ? AND provider_subject = ?",
        Timestamp.from(at),
        provider,
        subject);
    jdbc.update(
        "UPDATE customer_account SET last_login_at = ? WHERE account_id = ?",
        Timestamp.from(at),
        accountId);
  }

  // ---------- 원인증 요청 ----------

  public Optional<AuthRequestRow> requestByKey(String sourceSystem, String idempotencyKey) {
    return jdbc
        .query(
            "SELECT * FROM social_auth_request WHERE source_system = ? AND idempotency_key = ?",
            (rs, i) -> request(rs),
            sourceSystem,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  public Optional<AuthRequestRow> request(String authRequestId) {
    return jdbc
        .query(
            "SELECT * FROM social_auth_request WHERE auth_request_id = ?",
            (rs, i) -> request(rs),
            authRequestId)
        .stream()
        .findFirst();
  }

  public void insertRequest(
      String authRequestId,
      String sourceSystem,
      String idempotencyKey,
      String deviceId,
      String provider,
      String state,
      @Nullable String failureCode,
      @Nullable String accountId,
      boolean newAccount,
      Instant at) {
    jdbc.update(
        "INSERT INTO social_auth_request (auth_request_id, source_system, idempotency_key, device_id,"
            + " provider, state, failure_code, account_id, new_account, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        authRequestId,
        sourceSystem,
        idempotencyKey,
        deviceId,
        provider,
        state,
        failureCode,
        accountId,
        newAccount,
        Timestamp.from(at));
  }

  // ---------- 실패 제한 ----------

  public int failuresSince(String deviceId, Instant since) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM social_auth_request WHERE device_id = ?"
                + " AND failure_code = 'PROVIDER_REJECTED' AND created_at > ?",
            Integer.class,
            deviceId,
            Timestamp.from(since));
    return n == null ? 0 : n;
  }

  public Optional<Instant> restrictedUntil(String deviceId) {
    return jdbc
        .queryForList(
            "SELECT restricted_until FROM social_auth_restriction WHERE device_id = ?",
            Timestamp.class,
            deviceId)
        .stream()
        .findFirst()
        .map(Timestamp::toInstant);
  }

  public void restrict(String deviceId, Instant until) {
    jdbc.update(
        "INSERT INTO social_auth_restriction (device_id, restricted_until) VALUES (?,?)"
            + " ON CONFLICT (device_id) DO UPDATE SET restricted_until = EXCLUDED.restricted_until",
        deviceId,
        Timestamp.from(until));
  }

  /** 같은 기기의 요청을 직렬화(실패 집계·제한 판정 경합 방지). */
  public void lockDevice(String deviceId) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, "social-auth:" + deviceId);
  }

  private static AuthRequestRow request(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new AuthRequestRow(
        rs.getString("auth_request_id"),
        rs.getString("device_id"),
        rs.getString("provider"),
        rs.getString("state"),
        rs.getString("failure_code"),
        rs.getString("account_id"),
        rs.getBoolean("new_account"),
        rs.getTimestamp("created_at").toInstant());
  }
}
