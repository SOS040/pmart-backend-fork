package com.pmart.cases.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** customer CustomerRestricted 사본(V23 {@code customer_restriction_copy}, 2026-11-23 CU-7). */
@Repository
public class CustomerRestrictionStore {

  private final JdbcTemplate jdbc;

  public CustomerRestrictionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 더 새 차수일 때만 바꾼다. */
  public void upsert(String accountId, String restriction, @Nullable Instant until, long version) {
    jdbc.update(
        "INSERT INTO customer_restriction_copy (account_id, restriction, until_at, version)"
            + " VALUES (?,?,?,?) ON CONFLICT (account_id) DO UPDATE SET restriction ="
            + " EXCLUDED.restriction, until_at = EXCLUDED.until_at, version = EXCLUDED.version"
            + " WHERE customer_restriction_copy.version < EXCLUDED.version",
        accountId,
        restriction,
        until == null ? null : Timestamp.from(until),
        version);
  }

  /** 기한 안의 RESTRICTED·SUSPENDED 면 그 기한. */
  public Optional<Instant> activeUntil(String accountId, Instant now) {
    return jdbc
        .queryForList(
            "SELECT until_at FROM customer_restriction_copy WHERE account_id = ?"
                + " AND restriction IN ('RESTRICTED', 'SUSPENDED') AND until_at > ?",
            Timestamp.class,
            accountId,
            Timestamp.from(now))
        .stream()
        .findFirst()
        .map(Timestamp::toInstant);
  }
}
