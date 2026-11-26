package com.pmart.customer.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * 직원 고객 목록 규칙(2026-11-26 CU-5 — 2026-11-23 사용자 "추천대로"): 이름은 2글자 이상 앞부분 일치(별명은 찾지 않음), 가입일은
 * Asia/Seoul 날짜(둘 다 포함), 가입 최근 순 keyset 커서. 이름 검색은 감사 기록 대상(검색어는 남기지 않음).
 */
public final class StaffCustomerListRules {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  public static final int NAME_MIN = 2;
  public static final int NAME_MAX = 50;
  public static final int DEFAULT_SIZE = 50;
  public static final int MAX_SIZE = 200;

  /** 가입 시각·고객 ID 커서. */
  public record Cursor(Instant joinedAt, String customerId) {}

  /** 가입 시각 범위 [from, toExclusive) — 둘 다 null 가능. */
  public record JoinedRange(@Nullable Instant from, @Nullable Instant toExclusive) {}

  private StaffCustomerListRules() {}

  /** 이름 거르기 — 앞뒤 공백 제거, 비면 null. */
  public static @Nullable String name(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    String v = raw.strip();
    return v.isEmpty() ? null : v;
  }

  /** 문제가 있으면 사유, 없으면 null. */
  public static @Nullable String nameProblem(@Nullable String name) {
    if (name == null) {
      return null;
    }
    int n = name.codePointCount(0, name.length());
    if (n < NAME_MIN || n > NAME_MAX) {
      return "name 은 " + NAME_MIN + "~" + NAME_MAX + "자여야 합니다(앞부분 일치).";
    }
    return null;
  }

  /** LIKE 앞부분 일치 패턴(와일드카드 escape — escape 문자는 백슬래시). */
  public static String prefixPattern(String name) {
    return name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  public static @Nullable String rangeProblem(@Nullable LocalDate from, @Nullable LocalDate to) {
    if (from != null && to != null && from.isAfter(to)) {
      return "joinedFrom 은 joinedTo 보다 늦을 수 없습니다.";
    }
    return null;
  }

  public static JoinedRange range(@Nullable LocalDate from, @Nullable LocalDate to) {
    return new JoinedRange(
        from == null ? null : from.atStartOfDay(SEOUL).toInstant(),
        to == null ? null : to.plusDays(1).atStartOfDay(SEOUL).toInstant());
  }

  public static @Nullable String sizeProblem(int size) {
    if (size < 1 || size > MAX_SIZE) {
      return "size 는 1~" + MAX_SIZE + " 이어야 합니다.";
    }
    return null;
  }

  public static String encode(Cursor c) {
    String raw =
        c.joinedAt().getEpochSecond() + ":" + c.joinedAt().getNano() + ":" + c.customerId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** 잘못된 커서면 null. */
  public static @Nullable Cursor decode(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      String[] p = raw.split(":", 3);
      if (p.length != 3 || p[2].isEmpty()) {
        return null;
      }
      return new Cursor(Instant.ofEpochSecond(Long.parseLong(p[0]), Long.parseLong(p[1])), p[2]);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
