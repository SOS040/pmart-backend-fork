package com.pmart.auth.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 네이버 교환 때 받은 이메일 잠깐 보관 {@code naver_profile_email}(2026-11-21 BR-77). 키 = SHA-256("NAVER:" + sub).
 * 같은 sub 의 검증된 id_token 으로 로그인할 때 {@link #take} 로 한 번 읽고 지운다. 이메일은 로그 금지.
 */
@Repository
public class NaverProfileEmailStore {

  private final JdbcTemplate jdbc;

  public NaverProfileEmailStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public static String hash(String subject) {
    try {
      byte[] d =
          MessageDigest.getInstance("SHA-256")
              .digest(("NAVER:" + subject).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 기록(같은 계정이 다시 교환하면 새 값·새 만료). */
  public void save(String subject, String email, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO naver_profile_email (subject_hash, email, expires_at) VALUES (?,?,?)"
            + " ON CONFLICT (subject_hash) DO UPDATE SET email = EXCLUDED.email, expires_at = EXCLUDED.expires_at",
        hash(subject),
        email,
        Timestamp.from(expiresAt));
  }

  /** 만료 전이면 이메일을 주고 행을 지운다. 없거나 만료면 null(만료 행도 지운다). */
  public @Nullable String take(String subject, Instant now) {
    List<@Nullable String> live =
        jdbc.query(
            "DELETE FROM naver_profile_email WHERE subject_hash = ? RETURNING email, expires_at",
            (rs, i) ->
                rs.getTimestamp("expires_at").toInstant().isAfter(now)
                    ? rs.getString("email")
                    : null,
            hash(subject));
    return live.isEmpty() ? null : live.get(0);
  }

  /** 만료가 지난 행을 최대 {@code limit} 행 지운다. */
  public int purgeExpired(Instant now, int limit) {
    return jdbc.update(
        "DELETE FROM naver_profile_email WHERE subject_hash IN ("
            + "SELECT subject_hash FROM naver_profile_email WHERE expires_at < ? LIMIT ?)",
        Timestamp.from(now),
        limit);
  }
}
