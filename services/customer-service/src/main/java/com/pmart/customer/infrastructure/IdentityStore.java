package com.pmart.customer.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 계정의 확인된 휴대폰 번호(V13, 2026-11-20 ADR-0074). 유효한(풀리지 않은) 번호 해시는 한 계정만 — 부분 고유 색인 {@code
 * ux_customer_identity_phone}. 번호 원문은 로그에 남기지 않는다.
 */
@Repository
public class IdentityStore {

  private final JdbcTemplate jdbc;

  public IdentityStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 계정의 유효한 확인 시각(없으면 빈 값). */
  public Optional<Instant> verifiedAt(String accountId) {
    return jdbc
        .query(
            "SELECT verified_at FROM customer_identity WHERE account_id = ? AND released_at IS NULL",
            (rs, i) -> rs.getTimestamp(1).toInstant(),
            accountId)
        .stream()
        .findFirst();
  }

  /** 그 번호를 지금 쓰는 계정(없으면 빈 값). */
  public Optional<String> activeOwner(String phoneHash) {
    return jdbc
        .query(
            "SELECT account_id FROM customer_identity WHERE phone_hash = ? AND released_at IS NULL",
            (rs, i) -> rs.getString(1),
            phoneHash)
        .stream()
        .findFirst();
  }

  /** 다른 계정이 그 번호를 마지막으로 놓은 때(탈퇴). 없으면 빈 값. */
  public Optional<Instant> lastReleasedByOther(String phoneHash, String accountId) {
    Timestamp t =
        jdbc.queryForObject(
            "SELECT max(released_at) FROM customer_identity"
                + " WHERE phone_hash = ? AND released_at IS NOT NULL AND account_id <> ?",
            Timestamp.class,
            phoneHash,
            accountId);
    return Optional.ofNullable(t).map(Timestamp::toInstant);
  }

  /**
   * 계정의 확인 번호로 남긴다(있으면 새 번호로 바꾼다). 다른 계정이 이미 쓰는 번호면 고유 색인 위반(DuplicateKeyException) — 호출자가 트랜잭션째
   * 되돌리고 다시 판정한다.
   */
  public void claim(String accountId, String phoneHash, String phone, Instant at) {
    jdbc.update(
        "INSERT INTO customer_identity (account_id, phone_hash, phone, verified_at) VALUES (?,?,?,?)"
            + " ON CONFLICT (account_id) DO UPDATE SET phone_hash = EXCLUDED.phone_hash,"
            + " phone = EXCLUDED.phone, verified_at = EXCLUDED.verified_at, released_at = NULL",
        accountId,
        phoneHash,
        phone,
        Timestamp.from(at));
  }
}
