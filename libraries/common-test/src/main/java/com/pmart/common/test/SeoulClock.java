package com.pmart.common.test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 테스트용 고정 시계. 업무 코드는 {@link Clock} 을 주입받아 {@code Instant.now(clock)} 을 쓴다(ADR-0010). 기한
 * 경계·월말·자정(UTC 15:00) 사례를 재현하기 위해 Asia/Seoul 시각으로 고정한다.
 */
public final class SeoulClock {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  private SeoulClock() {}

  /** 서울 시각으로 고정된 Clock (내부 zone 은 UTC — 저장·전송은 UTC 이기 때문). */
  public static Clock fixedAt(LocalDate date, LocalTime time) {
    Instant instant = LocalDateTime.of(date, time).atZone(SEOUL).toInstant();
    return Clock.fixed(instant, ZoneId.of("UTC"));
  }

  public static Clock fixedAt(String isoLocalDateTimeSeoul) {
    Instant instant = LocalDateTime.parse(isoLocalDateTimeSeoul).atZone(SEOUL).toInstant();
    return Clock.fixed(instant, ZoneId.of("UTC"));
  }

  /** 서울 자정 직전/직후 (UTC 14:59:59 / 15:00:00). */
  public static Instant seoulMidnight(LocalDate date) {
    return date.atStartOfDay(SEOUL).toInstant();
  }
}
