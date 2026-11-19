package com.pmart.customer.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 휴대폰 번호 확인(V12, 2026-11-19). */
@Repository
public class PhoneVerificationStore {

  public record Row(
      String verificationId,
      String accountId,
      String phoneHash,
      String code,
      String state,
      int attempts,
      @Nullable String failureReason,
      Instant expiresAt,
      Instant createdAt,
      @Nullable Instant finishedAt) {}

  private final JdbcTemplate jdbc;

  public PhoneVerificationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int startsSince(String accountId, Instant since) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM phone_verification WHERE account_id = ? AND created_at >= ?",
            Integer.class,
            accountId,
            Timestamp.from(since));
    return n == null ? 0 : n;
  }

  /** 새 확인 — 이 계정의 대기 중인 확인은 EXPIRED 로 끝낸다. */
  @Transactional
  public void start(Row r) {
    start(r, null);
  }

  /**
   * 새 확인(가입 단계 — 2026-11-20 ADR-0074): 프로필이 없을 수 있어 번호 원문을 확인 행에 둔다(OCTOMO 조회용). /me 확인은 null(프로필
   * 번호를 쓴다).
   */
  @Transactional
  public void start(Row r, @Nullable String phone) {
    jdbc.update(
        "UPDATE phone_verification SET state = 'EXPIRED', finished_at = ?"
            + " WHERE account_id = ? AND state = 'PENDING'",
        Timestamp.from(r.createdAt()),
        r.accountId());
    jdbc.update(
        "INSERT INTO phone_verification (verification_id, account_id, phone_hash, code, state, attempts,"
            + " expires_at, created_at, phone) VALUES (?,?,?,?,'PENDING',0,?,?,?)",
        r.verificationId(),
        r.accountId(),
        r.phoneHash(),
        r.code(),
        Timestamp.from(r.expiresAt()),
        Timestamp.from(r.createdAt()),
        phone);
  }

  /** 가입 단계 확인의 번호 원문(/me 확인이면 빈 값). */
  public Optional<String> phone(String verificationId) {
    return jdbc
        .query(
            "SELECT phone FROM phone_verification WHERE verification_id = ? AND phone IS NOT NULL",
            (rs, i) -> rs.getString(1),
            verificationId)
        .stream()
        .findFirst();
  }

  /** 확인됨(대기 중일 때만) — 번호 규칙·프로필은 IdentityService 가 같은 트랜잭션에서. */
  public boolean markVerified(String verificationId, Instant at) {
    return jdbc.update(
            "UPDATE phone_verification SET state = 'VERIFIED', finished_at = ?"
                + " WHERE verification_id = ? AND state = 'PENDING'",
            Timestamp.from(at),
            verificationId)
        == 1;
  }

  public Optional<Row> find(String verificationId) {
    return jdbc
        .query(
            "SELECT * FROM phone_verification WHERE verification_id = ?",
            (rs, i) ->
                new Row(
                    rs.getString("verification_id"),
                    rs.getString("account_id"),
                    rs.getString("phone_hash"),
                    rs.getString("code"),
                    rs.getString("state"),
                    rs.getInt("attempts"),
                    rs.getString("failure_reason"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("finished_at") == null
                        ? null
                        : rs.getTimestamp("finished_at").toInstant()),
            verificationId)
        .stream()
        .findFirst();
  }

  /** 대기 중일 때만 끝낸다. */
  public int finish(String verificationId, String state, @Nullable String reason, Instant at) {
    return jdbc.update(
        "UPDATE phone_verification SET state = ?, failure_reason = ?, finished_at = ?"
            + " WHERE verification_id = ? AND state = 'PENDING'",
        state,
        reason,
        Timestamp.from(at),
        verificationId);
  }

  /** "아직 안 왔음" 한 번 — 다 쓰면 FAILED·TOO_MANY_ATTEMPTS. */
  public int miss(String verificationId, int maxAttempts, Instant at) {
    return jdbc.update(
        "UPDATE phone_verification SET attempts = attempts + 1,"
            + " state = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE state END,"
            + " failure_reason = CASE WHEN attempts + 1 >= ? THEN 'TOO_MANY_ATTEMPTS' ELSE failure_reason END,"
            + " finished_at = CASE WHEN attempts + 1 >= ? THEN ? ELSE finished_at END"
            + " WHERE verification_id = ? AND state = 'PENDING'",
        maxAttempts,
        maxAttempts,
        maxAttempts,
        Timestamp.from(at),
        verificationId);
  }

  /** 확인됨 — 확인과 프로필 phone_verified 를 함께(번호가 그대로일 때만, 프로필 version +1). */
  @Transactional
  public boolean verify(String verificationId, String accountId, String phone, Instant at) {
    int v =
        jdbc.update(
            "UPDATE phone_verification SET state = 'VERIFIED', finished_at = ?"
                + " WHERE verification_id = ? AND state = 'PENDING'",
            Timestamp.from(at),
            verificationId);
    if (v == 0) {
      return false;
    }
    jdbc.update(
        "UPDATE customer SET phone_verified = true, version = version + 1, updated_at = ?"
            + " WHERE account_id = ? AND phone = ? AND NOT phone_verified",
        Timestamp.from(at),
        accountId,
        phone);
    return true;
  }
}
