package com.pmart.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * 탈퇴한 소셜 계정 식별값 규칙(2026-11-20 ADR-0077 결정 5). 원래 sub 는 남기지 않고 {@code SHA-256("provider:subject")}
 * hex 만, 보관은 탈퇴 완료 + 5년.
 */
public final class WithdrawnIdentifierRules {

  /** 보관 기간(년). 개인정보 처리방침 2026-11-20판 §3. */
  public static final int RETENTION_YEARS = 5;

  private WithdrawnIdentifierRules() {}

  /** {@code SHA-256("provider:subject")} 소문자 hex 64자. */
  public static String hash(String provider, String subject) {
    try {
      byte[] d =
          MessageDigest.getInstance("SHA-256")
              .digest((provider + ":" + subject).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 파기 시각 = 탈퇴 완료 + 5년(UTC 달력 기준 — 2월 29일은 2월 28일). */
  public static Instant expiresAt(Instant withdrawnAt) {
    return withdrawnAt.atOffset(ZoneOffset.UTC).plusYears(RETENTION_YEARS).toInstant();
  }
}
