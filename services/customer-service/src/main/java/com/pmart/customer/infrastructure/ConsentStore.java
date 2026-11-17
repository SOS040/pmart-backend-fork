package com.pmart.customer.infrastructure;

import com.pmart.customer.domain.ConsentItem;
import com.pmart.customer.domain.ConsentRules.Stored;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 약관 동의 저장(JDBC) — 계정·항목별 마지막 결정(customer_consent)과 바뀐 기록(customer_consent_history). */
@Repository
public class ConsentStore {

  private final JdbcTemplate jdbc;

  public ConsentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 계정의 마지막 결정들. 모르는 항목(예전 코드)은 건너뛴다. */
  public Map<ConsentItem, Stored> consents(String accountId) {
    Map<ConsentItem, Stored> out = new EnumMap<>(ConsentItem.class);
    jdbc.query(
        "SELECT consent_type, agreed, terms_version, agreed_at, withdrawn_at, channel, version,"
            + " changed_at FROM customer_consent WHERE account_id = ?",
        rs -> {
          ConsentItem item;
          try {
            item = ConsentItem.valueOf(rs.getString("consent_type"));
          } catch (IllegalArgumentException e) {
            return;
          }
          out.put(
              item,
              new Stored(
                  item,
                  rs.getBoolean("agreed"),
                  rs.getString("terms_version"),
                  instant(rs.getTimestamp("agreed_at")),
                  instant(rs.getTimestamp("withdrawn_at")),
                  rs.getString("channel"),
                  rs.getInt("version"),
                  rs.getTimestamp("changed_at").toInstant()));
        },
        accountId);
    return out;
  }

  /** 마지막 결정을 바꾼다(없으면 만든다). 바뀐 뒤 차수(1부터). 같은 계정의 변경은 호출자가 customer 행 잠금으로 직렬화한다. */
  public int save(
      String accountId,
      ConsentItem item,
      boolean agreed,
      String termsVersion,
      @Nullable Instant agreedAt,
      @Nullable Instant withdrawnAt,
      String channel,
      Instant now) {
    Integer version =
        jdbc.queryForObject(
            "INSERT INTO customer_consent (account_id, consent_type, agreed, terms_version, agreed_at,"
                + " withdrawn_at, channel, version, changed_at) VALUES (?,?,?,?,?,?,?,1,?)"
                + " ON CONFLICT (account_id, consent_type) DO UPDATE SET agreed = EXCLUDED.agreed,"
                + " terms_version = EXCLUDED.terms_version, agreed_at = EXCLUDED.agreed_at,"
                + " withdrawn_at = EXCLUDED.withdrawn_at, channel = EXCLUDED.channel,"
                + " version = customer_consent.version + 1, changed_at = EXCLUDED.changed_at"
                + " RETURNING version",
            Integer.class,
            accountId,
            item.name(),
            agreed,
            termsVersion,
            ts(agreedAt),
            ts(withdrawnAt),
            channel,
            ts(now));
    return version == null ? 1 : version;
  }

  public void appendHistory(
      String accountId,
      ConsentItem item,
      boolean agreed,
      String termsVersion,
      String channel,
      int version,
      String requestId,
      Instant now) {
    jdbc.update(
        "INSERT INTO customer_consent_history (account_id, consent_type, agreed, terms_version,"
            + " channel, version, request_id, changed_at) VALUES (?,?,?,?,?,?,?,?)",
        accountId,
        item.name(),
        agreed,
        termsVersion,
        channel,
        version,
        requestId,
        ts(now));
  }

  /** 동의 변경 결과(멱등 기록 request_record 의 결과). 본인(actor) 것만. */
  public Optional<String> changeResult(String accountId, String requestId, String operation) {
    return jdbc
        .queryForList(
            "SELECT result::text FROM request_record"
                + " WHERE request_id = ? AND actor = ? AND operation = ?",
            String.class,
            requestId,
            accountId,
            operation)
        .stream()
        .findFirst();
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
