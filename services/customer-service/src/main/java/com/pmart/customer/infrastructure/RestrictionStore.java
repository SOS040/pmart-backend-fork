package com.pmart.customer.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 계정 제한(V19, 계약 0.16.0 CU-7) — 지금 값은 {@code customer} 칸, 이력은 {@code customer_restriction_history}.
 */
@Repository
public class RestrictionStore {

  public record Record(
      String recordId,
      String accountId,
      String restriction,
      String previous,
      @Nullable Instant until,
      String source,
      @Nullable String reason,
      String actor,
      Instant createdAt) {}

  private final JdbcTemplate jdbc;

  public RestrictionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 계정 행 잠금 뒤 부른다. 바꾼 뒤의 restriction_version. */
  public int set(String accountId, String restriction, @Nullable Instant until, Instant now) {
    Integer v =
        jdbc.queryForObject(
            "UPDATE customer SET restriction = ?, restriction_until = ?,"
                + " restriction_version = restriction_version + 1, updated_at = ?"
                + " WHERE account_id = ? RETURNING restriction_version",
            Integer.class,
            restriction,
            until == null ? null : Timestamp.from(until),
            Timestamp.from(now),
            accountId);
    return v == null ? 0 : v;
  }

  /** 같은 recordId(자동 정지는 사건 ID)면 넣지 않는다. 넣었으면 true. */
  public boolean insert(Record r) {
    return jdbc.update(
            "INSERT INTO customer_restriction_history (record_id, account_id, restriction, previous,"
                + " until_at, source, reason, actor, created_at) VALUES (?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (record_id) DO NOTHING",
            r.recordId(),
            r.accountId(),
            r.restriction(),
            r.previous(),
            r.until() == null ? null : Timestamp.from(r.until()),
            r.source(),
            r.reason(),
            r.actor(),
            Timestamp.from(r.createdAt()))
        == 1;
  }

  public List<Record> history(String accountId, int limit) {
    return jdbc.query(
        "SELECT * FROM customer_restriction_history WHERE account_id = ?"
            + " ORDER BY created_at DESC, record_id DESC LIMIT ?",
        (rs, i) ->
            new Record(
                rs.getString("record_id"),
                rs.getString("account_id"),
                rs.getString("restriction"),
                rs.getString("previous"),
                rs.getTimestamp("until_at") == null
                    ? null
                    : rs.getTimestamp("until_at").toInstant(),
                rs.getString("source"),
                rs.getString("reason"),
                rs.getString("actor"),
                rs.getTimestamp("created_at").toInstant()),
        accountId,
        limit);
  }
}
