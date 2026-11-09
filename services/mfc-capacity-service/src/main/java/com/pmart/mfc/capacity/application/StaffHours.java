package com.pmart.mfc.capacity.application;

import com.pmart.mfc.capacity.infrastructure.CenterCalendarClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 근무를 등록하지 않은 작업자의 근무 = 센터 직원 근무 시간표(center-master STAFF, 사용자 결정 2026-11-09 — 풀타임은 센터 운영시간, Q-09
 * 일부). 날짜별 시간표를 5분 동안 기억한다(다음 작업 받기마다 부르지 않게).
 */
@Component
public class StaffHours {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final Duration CACHE = Duration.ofMinutes(5);

  private record Cached(List<Instant[]> windows, Instant at) {}

  private final CenterCalendarClient calendar;
  private final Clock clock;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public StaffHours(CenterCalendarClient calendar, Clock clock) {
    this.calendar = calendar;
    this.clock = clock;
  }

  /** [from, to] 와 겹치는 날들의 직원 근무 구간. 시간표가 없으면 CONFIG_REQUIRED(호출자에게 그대로). */
  public List<Instant[]> windows(String mfcId, Instant from, Instant to) {
    List<Instant[]> out = new ArrayList<>();
    LocalDate d = LocalDate.ofInstant(from, SEOUL).minusDays(1); // 전날 밤을 넘는 구간 대비
    LocalDate last = LocalDate.ofInstant(to, SEOUL);
    for (; !d.isAfter(last); d = d.plusDays(1)) {
      out.addAll(day(mfcId, d));
    }
    return out;
  }

  /** 지금이 직원 근무 시간 밖인가. */
  public boolean outside(String mfcId, Instant now) {
    return windows(mfcId, now, now).stream()
        .noneMatch(w -> !now.isBefore(w[0]) && now.isBefore(w[1]));
  }

  private List<Instant[]> day(String mfcId, LocalDate date) {
    String key = mfcId + "|" + date;
    Instant now = clock.instant();
    Cached c = cache.get(key);
    if (c != null && Duration.between(c.at(), now).compareTo(CACHE) < 0) {
      return c.windows();
    }
    List<Instant[]> w = windowsOf(date, calendar.staffDay(mfcId, date));
    cache.put(key, new Cached(w, now));
    return w;
  }

  /** CalendarDay → 구간. 휴일·영업 안 함이면 없음. "24:00" 은 다음 날 0시. */
  static List<Instant[]> windowsOf(LocalDate date, JsonNode day) {
    List<Instant[]> out = new ArrayList<>();
    if (day.path("isHoliday").asBoolean(false) || !day.path("open").asBoolean(true)) {
      return out;
    }
    for (JsonNode r : day.path("ranges")) {
      Instant s = at(date, r.path("start").asString("00:00"));
      Instant e = at(date, r.path("end").asString("24:00"));
      if (e.isAfter(s)) {
        out.add(new Instant[] {s, e});
      }
    }
    return out;
  }

  private static Instant at(LocalDate date, String hhmm) {
    if ("24:00".equals(hhmm)) {
      return date.plusDays(1).atStartOfDay(SEOUL).toInstant();
    }
    return date.atTime(LocalTime.parse(hhmm)).atZone(SEOUL).toInstant();
  }
}
