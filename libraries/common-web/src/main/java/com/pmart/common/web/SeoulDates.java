package com.pmart.common.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * ADR-0010: 저장·전송은 UTC {@link Instant}, 기한·영업일·달력 판정은 Asia/Seoul.
 *
 * <ul>
 *   <li>달력 월 더하기는 말일 클램프 (1/31 + 1개월 = 2/28·29).
 *   <li>날짜형 기한(소비기한)은 "해당 일 00:00 Asia/Seoul 에 차단".
 *   <li>경계 포함 여부는 호출자가 명시한다. 반품 접수는 경계시각까지 가능(≤).
 * </ul>
 */
public final class SeoulDates {

  public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

  private SeoulDates() {}

  public static LocalDate toLocalDate(Instant instant) {
    return instant.atZone(ZONE).toLocalDate();
  }

  public static ZonedDateTime toZoned(Instant instant) {
    return instant.atZone(ZONE);
  }

  /** 해당 날짜의 00:00 Asia/Seoul. */
  public static Instant startOfDay(LocalDate date) {
    return date.atStartOfDay(ZONE).toInstant();
  }

  /** 해당 날짜의 다음날 00:00 Asia/Seoul (반열림 구간 [start, end) 의 end). "24:00" 표기와 같다. */
  public static Instant endOfDayExclusive(LocalDate date) {
    return date.plusDays(1).atStartOfDay(ZONE).toInstant();
  }

  /** 날짜형 기한: 그 날 00:00 부터 사용 불가. (소비기한 당일 차단 규칙) */
  public static boolean isExpiredOn(LocalDate expiryDate, Instant at) {
    return !at.isBefore(startOfDay(expiryDate));
  }

  /** 기준 시각 + n일 (같은 시각). 반품 7일, 신선 24시간 등. 경계 포함(≤)은 {@link #isWithinInclusive}. */
  public static Instant plusDays(Instant from, long days) {
    return from.atZone(ZONE).plusDays(days).toInstant();
  }

  public static Instant plusHours(Instant from, long hours) {
    return from.atZone(ZONE).plusHours(hours).toInstant();
  }

  /** 기준 시각 + n개월 (달력 월, 말일 클램프). 교환 3개월. */
  public static Instant plusCalendarMonths(Instant from, long months) {
    return from.atZone(ZONE).plusMonths(months).toInstant();
  }

  /** at ≤ deadline (경계 포함). */
  public static boolean isWithinInclusive(Instant at, Instant deadline) {
    return !at.isAfter(deadline);
  }

  /** at < deadline (경계 제외). TMS 시간창 [start, end). */
  public static boolean isBeforeExclusive(Instant at, Instant deadline) {
    return at.isBefore(deadline);
  }

  /** Asia/Seoul 날짜·시각 → Instant (예: 슬롯 13:00). */
  public static Instant at(LocalDate date, LocalTime time) {
    return LocalDateTime.of(date, time).atZone(ZONE).toInstant();
  }

  /** 두 Instant가 Asia/Seoul 기준 같은 날짜인지. 영업일 집계용. */
  public static boolean isSameSeoulDay(Instant a, Instant b) {
    return toLocalDate(a).equals(toLocalDate(b));
  }
}
