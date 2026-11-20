package com.pmart.auth.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 탈퇴한 소셜 계정 식별값 격리 표 {@code withdrawn_social_identifier}(2026-11-20 ADR-0077 결정 5). 쓰기(탈퇴 완료)와 만료
 * 파기만 둔다 — 읽기는 첫 구매 혜택 중복 판정을 만들 때 그 한 곳에만 더한다. 회원 표와 조인하지 않는다.
 */
@Repository
public class WithdrawnIdentifierStore {

  private final JdbcTemplate jdbc;

  public WithdrawnIdentifierStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 기록. 같은 소셜 계정이 다시 가입·탈퇴했으면 더 늦은 탈퇴로 보관 기간을 다시 센다. */
  public void save(String identifierHash, String provider, Instant withdrawnAt, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO withdrawn_social_identifier (identifier_hash, provider, withdrawn_at, expires_at)"
            + " VALUES (?,?,?,?) ON CONFLICT (identifier_hash) DO UPDATE"
            + " SET withdrawn_at = EXCLUDED.withdrawn_at, expires_at = EXCLUDED.expires_at"
            + " WHERE withdrawn_social_identifier.withdrawn_at < EXCLUDED.withdrawn_at",
        identifierHash,
        provider,
        Timestamp.from(withdrawnAt),
        Timestamp.from(expiresAt));
  }

  /** 만료가 지난 행을 최대 {@code limit} 행 지운다. 지운 수. */
  public int purgeExpired(Instant now, int limit) {
    return jdbc.update(
        "DELETE FROM withdrawn_social_identifier WHERE identifier_hash IN ("
            + "SELECT identifier_hash FROM withdrawn_social_identifier WHERE expires_at < ? LIMIT ?)",
        Timestamp.from(now),
        limit);
  }
}
