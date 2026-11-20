package com.pmart.auth.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 고객 계정·제공자 연결·원인증 요청·기기 제한 저장(JDBC). 제공자 토큰·이메일은 저장하지 않는다. */
@Repository
public class CustomerStore {

  /**
   * @param requiredConsents AUTH_CONSENT 일 때 반드시 동의할 항목 {@code TYPE:version}
   * @param optionalConsents 함께 보여 준 선택 항목
   * @param completionKey 동의 완료(completions)의 Idempotency-Key — 끝났으면 있음
   */
  public record AuthRequestRow(
      String authRequestId,
      String deviceId,
      String provider,
      String state,
      @Nullable String failureCode,
      @Nullable String accountId,
      boolean newAccount,
      Instant createdAt,
      List<String> requiredConsents,
      List<String> optionalConsents,
      @Nullable String completionKey) {}

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

  /** 가입을 마친(한 번이라도 회원이 된) 계정인가. 동의 단계에서 나간 가입은 false — 다음 로그인도 가입으로 본다. */
  public boolean everMember(String accountId) {
    return !jdbc.queryForList(
            "SELECT 1 FROM customer_account WHERE account_id = ? AND member_since IS NOT NULL",
            accountId)
        .isEmpty();
  }

  /** 회원이 됨(처음 한 번만 기록). */
  public void markMember(String accountId, Instant at) {
    jdbc.update(
        "UPDATE customer_account SET member_since = COALESCE(member_since, ?) WHERE account_id = ?",
        Timestamp.from(at),
        accountId);
  }

  /** AUTH_CONSENT 로 남긴 요청이 무엇을 물었는지(같은 키 재전송·결과 조회가 같은 목록을 준다). */
  public void recordConsentsAsked(
      String authRequestId, List<String> requiredConsents, List<String> optionalConsents) {
    jdbc.update(
        "UPDATE social_auth_request SET required_consents = ?, optional_consents = ?"
            + " WHERE auth_request_id = ?",
        join(requiredConsents),
        join(optionalConsents),
        authRequestId);
  }

  /** 동의 완료 직렬화 — 같은 원인증의 completions 가 겹치지 않게 행을 잠그고 다시 읽는다. */
  public Optional<AuthRequestRow> lockRequest(String authRequestId) {
    return jdbc
        .query(
            "SELECT * FROM social_auth_request WHERE auth_request_id = ? FOR UPDATE",
            (rs, i) -> request(rs),
            authRequestId)
        .stream()
        .findFirst();
  }

  /** AUTH_CONSENT → AUTH_MEMBER(동의 완료). */
  public void completeConsent(String authRequestId, String completionKey, Instant at) {
    jdbc.update(
        "UPDATE social_auth_request SET state = 'AUTH_MEMBER', completion_key = ?, completed_at = ?"
            + " WHERE auth_request_id = ? AND state = 'AUTH_CONSENT'",
        completionKey,
        Timestamp.from(at),
        authRequestId);
  }

  // ---------- 휴대폰 확인 단계(2026-11-20 ADR-0074) ----------

  /** 동의는 끝났고 휴대폰 확인이 남음: AUTH_CONSENT → AUTH_IDENTITY. */
  public void consentDoneIdentityNext(String authRequestId) {
    jdbc.update(
        "UPDATE social_auth_request SET state = 'AUTH_IDENTITY'"
            + " WHERE auth_request_id = ? AND state = 'AUTH_CONSENT'",
        authRequestId);
  }

  /** 휴대폰 확인 완료: AUTH_IDENTITY → AUTH_MEMBER. */
  public void completeIdentity(String authRequestId, String completionKey, Instant at) {
    jdbc.update(
        "UPDATE social_auth_request SET state = 'AUTH_MEMBER', completion_key = ?, completed_at = ?"
            + " WHERE auth_request_id = ? AND state = 'AUTH_IDENTITY'",
        completionKey,
        Timestamp.from(at),
        authRequestId);
  }

  /** 휴대폰 확인이 막힘(다른 계정의 번호·재가입 막기) — 이 원인증은 끝(AUTH_GUEST·사유). */
  public void failIdentity(String authRequestId, String code) {
    jdbc.update(
        "UPDATE social_auth_request SET state = 'AUTH_GUEST', failure_code = ?"
            + " WHERE auth_request_id = ? AND state = 'AUTH_IDENTITY'",
        code,
        authRequestId);
  }

  /**
   * 회원이 된 적 없는 계정을 버린다(제공자 연결·계정) — 같은 번호의 다른 계정이 있어 가입을 확정하지 않을 때(ADR-0074 R2 "만들었으면 버린다"). 그 소셜
   * 계정의 다음 로그인은 새 가입이다. 회원이 된 적 있으면 아무것도 하지 않는다.
   *
   * @return 지웠으면 true
   */
  public boolean discardIfNeverMember(String accountId) {
    if (everMember(accountId)) {
      return false;
    }
    jdbc.update("DELETE FROM social_identity WHERE account_id = ?", accountId);
    return jdbc.update(
            "DELETE FROM customer_account WHERE account_id = ? AND member_since IS NULL", accountId)
        == 1;
  }

  /** 계정의 가입 제공자(처음 연결된 것). */
  public Optional<String> provider(String accountId) {
    return jdbc
        .query(
            "SELECT provider FROM social_identity WHERE account_id = ? ORDER BY linked_at LIMIT 1",
            (rs, i) -> rs.getString(1),
            accountId)
        .stream()
        .findFirst();
  }

  /** 회원이 된 때(가입일). */
  public Optional<Instant> memberSince(String accountId) {
    return jdbc
        .query(
            "SELECT member_since FROM customer_account WHERE account_id = ? AND member_since IS NOT NULL",
            (rs, i) -> rs.getTimestamp(1).toInstant(),
            accountId)
        .stream()
        .findFirst();
  }

  // ---------- 고객 탈퇴(2026-11-20 ADR-0077) ----------

  /** 제공자 연결 한 줄(제공자, sub). sub 는 개인 식별값 — 로그 금지. */
  public record LinkedIdentity(String provider, String subject) {}

  /** 계정 상태를 행 잠금으로 읽는다(탈퇴 반영 직렬화). */
  public Optional<String> lockAccountState(String accountId) {
    return jdbc
        .queryForList(
            "SELECT state FROM customer_account WHERE account_id = ? FOR UPDATE",
            String.class,
            accountId)
        .stream()
        .findFirst();
  }

  public List<LinkedIdentity> identities(String accountId) {
    return jdbc.query(
        "SELECT provider, provider_subject FROM social_identity WHERE account_id = ?",
        (rs, i) -> new LinkedIdentity(rs.getString(1), rs.getString(2)),
        accountId);
  }

  /** 계정의 제공자 연결을 모두 지운다 — 같은 소셜 계정의 다음 로그인은 새 가입. */
  public int deleteIdentities(String accountId) {
    return jdbc.update("DELETE FROM social_identity WHERE account_id = ?", accountId);
  }

  /** 계정 사용 안 함(WITHDRAWN) — 이 accountId 로는 다시 토큰이 나가지 않는다. */
  public void markWithdrawn(String accountId) {
    jdbc.update("UPDATE customer_account SET state = 'WITHDRAWN' WHERE account_id = ?", accountId);
  }

  private static @Nullable String join(List<String> items) {
    return items.isEmpty() ? null : String.join(",", items);
  }

  private static List<String> split(@Nullable String joined) {
    return joined == null || joined.isBlank()
        ? List.of()
        : Arrays.stream(joined.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
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
        rs.getTimestamp("created_at").toInstant(),
        split(rs.getString("required_consents")),
        split(rs.getString("optional_consents")),
        rs.getString("completion_key"));
  }
}
