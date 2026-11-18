package com.pmart.customer.infrastructure;

import com.pmart.customer.domain.ConsentItem;
import com.pmart.customer.domain.ConsentRules.Stored;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 로그인 전(기기) 동의 저장(2026-11-19 BR-59, V9) — 기기·항목별 마지막 결정(customer_device_consent)과 계정으로 옮긴 기록
 * (customer_device_consent_migration). 옮기면 기기 결정은 지운다. 같은 기기의 변경·옮기기는 {@link #lock} 으로 줄 세운다.
 */
@Repository
public class DeviceConsentStore {

  /** 기기 동의의 받은 곳(계약 Consent.channel 에는 기기 값이 없어 null 로 보인다). */
  static final String DEVICE = "DEVICE";

  private final JdbcTemplate jdbc;

  public DeviceConsentStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 같은 기기의 변경·옮기기를 트랜잭션 끝까지 줄 세운다. */
  public void lock(String deviceId) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "device-consent:" + deviceId);
  }

  public Map<ConsentItem, Stored> consents(String deviceId) {
    Map<ConsentItem, Stored> out = new EnumMap<>(ConsentItem.class);
    jdbc.query(
        "SELECT consent_type, agreed, terms_version, agreed_at, withdrawn_at, changed_at"
            + " FROM customer_device_consent WHERE device_id = ?",
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
                  DEVICE,
                  0,
                  rs.getTimestamp("changed_at").toInstant()));
        },
        deviceId);
    return out;
  }

  public void save(
      String deviceId,
      ConsentItem item,
      boolean agreed,
      String termsVersion,
      @Nullable Instant agreedAt,
      @Nullable Instant withdrawnAt,
      Instant now) {
    jdbc.update(
        "INSERT INTO customer_device_consent (device_id, consent_type, agreed, terms_version,"
            + " agreed_at, withdrawn_at, changed_at) VALUES (?,?,?,?,?,?,?)"
            + " ON CONFLICT (device_id, consent_type) DO UPDATE SET agreed = EXCLUDED.agreed,"
            + " terms_version = EXCLUDED.terms_version, agreed_at = EXCLUDED.agreed_at,"
            + " withdrawn_at = EXCLUDED.withdrawn_at, changed_at = EXCLUDED.changed_at",
        deviceId,
        item.name(),
        agreed,
        termsVersion,
        ts(agreedAt),
        ts(withdrawnAt),
        ts(now));
  }

  /** 계정으로 옮긴 뒤 — 기기 결정을 지우고(다음 비회원은 다시 받는다) 옮긴 기록을 남긴다(0건이면 기록 없음). */
  public void cleared(String deviceId, String accountId, int count, Instant now) {
    jdbc.update("DELETE FROM customer_device_consent WHERE device_id = ?", deviceId);
    if (count > 0) {
      jdbc.update(
          "INSERT INTO customer_device_consent_migration (device_id, account_id, migrated_count,"
              + " migrated_at) VALUES (?,?,?,?)",
          deviceId,
          accountId,
          count,
          ts(now));
    }
  }

  private static @Nullable Instant instant(@Nullable Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static @Nullable Timestamp ts(@Nullable Instant i) {
    return i == null ? null : Timestamp.from(i);
  }
}
