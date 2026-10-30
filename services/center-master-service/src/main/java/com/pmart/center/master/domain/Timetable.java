package com.pmart.center.master.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 영업 시간표(요일별 시간대 + 휴일 + 임시 제한). 영업일·시각은 Asia/Seoul(ADR-0010). 시각은 "HH:mm", 끝은 "24:00" 까지(그날 자정).
 *
 * <p>임시 제한은 그 날의 시간대에서 겹치는 구간을 뺀다(제한이 우선). 휴일은 영업하지 않는다.
 */
public record Timetable(
    Map<DayOfWeek, List<Range>> weekly, Set<LocalDate> holidays, List<Restriction> restrictions) {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final Pattern HHMM = Pattern.compile("^([01][0-9]|2[0-3]):[0-5][0-9]$|^24:00$");

  /** [start, end) — 분 단위. end 는 최대 1440(24:00). */
  public record Range(int startMinute, int endMinute) {
    public static Range of(String start, String end) {
      Range r = new Range(minutes(start), minutes(end));
      if (r.startMinute >= r.endMinute) {
        throw new IllegalArgumentException("시간대 시작이 끝보다 늦거나 같다: " + start + "~" + end);
      }
      return r;
    }

    public String start() {
      return text(startMinute);
    }

    public String end() {
      return text(endMinute);
    }
  }

  public record Restriction(LocalDate date, Range range, @Nullable String reason) {}

  /** 하루 결과. ranges 는 제한을 뺀 실제 영업 구간. */
  public record Day(
      LocalDate date,
      boolean holiday,
      boolean open,
      List<Range> ranges,
      List<Restriction> restrictions) {}

  public Timetable {
    weekly = Map.copyOf(weekly);
    holidays = Set.copyOf(holidays);
    restrictions = List.copyOf(restrictions);
  }

  public Day day(LocalDate date) {
    List<Restriction> todays = restrictions.stream().filter(r -> r.date().equals(date)).toList();
    if (holidays.contains(date)) {
      return new Day(date, true, false, List.of(), todays);
    }
    List<Range> ranges = new ArrayList<>(weekly.getOrDefault(date.getDayOfWeek(), List.of()));
    for (Restriction r : todays) {
      ranges = subtract(ranges, r.range());
    }
    return new Day(date, false, !ranges.isEmpty(), List.copyOf(ranges), todays);
  }

  /** 이 시각에 영업 중인가(Asia/Seoul). */
  public boolean openAt(ZonedDateTime at) {
    ZonedDateTime local = at.withZoneSameInstant(SEOUL);
    int minute = local.getHour() * 60 + local.getMinute();
    return day(local.toLocalDate()).ranges().stream()
        .anyMatch(r -> r.startMinute() <= minute && minute < r.endMinute());
  }

  /** 시작일부터 days 일의 영업 구간(절대 시각) — CenterCalendarChanged windows. */
  public List<ZonedDateTime[]> windows(LocalDate from, int days) {
    List<ZonedDateTime[]> out = new ArrayList<>();
    for (int i = 0; i < days; i++) {
      LocalDate d = from.plusDays(i);
      for (Range r : day(d).ranges()) {
        out.add(
            new ZonedDateTime[] {
              d.atStartOfDay(SEOUL).plusMinutes(r.startMinute()),
              d.atStartOfDay(SEOUL).plusMinutes(r.endMinute())
            });
      }
    }
    return out;
  }

  /** 요일 이름(MON..SUN) → 시간대 목록 검증·변환. */
  public static Map<DayOfWeek, List<Range>> weekly(Map<String, List<String[]>> raw) {
    Map<DayOfWeek, List<Range>> out = new EnumMap<>(DayOfWeek.class);
    raw.forEach(
        (dow, ranges) -> {
          List<Range> list = new ArrayList<>();
          for (String[] r : ranges) {
            list.add(Range.of(r[0], r[1]));
          }
          list.sort((a, b) -> Integer.compare(a.startMinute(), b.startMinute()));
          for (int i = 1; i < list.size(); i++) {
            if (list.get(i).startMinute() < list.get(i - 1).endMinute()) {
              throw new IllegalArgumentException(dow + " 시간대가 겹친다");
            }
          }
          out.put(dayOfWeek(dow), list);
        });
    return out;
  }

  public static DayOfWeek dayOfWeek(String s) {
    return switch (s) {
      case "MON" -> DayOfWeek.MONDAY;
      case "TUE" -> DayOfWeek.TUESDAY;
      case "WED" -> DayOfWeek.WEDNESDAY;
      case "THU" -> DayOfWeek.THURSDAY;
      case "FRI" -> DayOfWeek.FRIDAY;
      case "SAT" -> DayOfWeek.SATURDAY;
      case "SUN" -> DayOfWeek.SUNDAY;
      default -> throw new IllegalArgumentException("요일 오류: " + s);
    };
  }

  public static String dayName(DayOfWeek d) {
    return d.name().substring(0, 3);
  }

  static int minutes(String hhmm) {
    if (hhmm == null || !HHMM.matcher(hhmm).matches()) {
      throw new IllegalArgumentException("시각 형식 오류(HH:mm, 끝은 24:00 까지): " + hhmm);
    }
    if ("24:00".equals(hhmm)) {
      return 1440;
    }
    LocalTime t = LocalTime.parse(hhmm);
    return t.getHour() * 60 + t.getMinute();
  }

  static String text(int minute) {
    return minute == 1440 ? "24:00" : String.format("%02d:%02d", minute / 60, minute % 60);
  }

  private static List<Range> subtract(List<Range> ranges, Range cut) {
    List<Range> out = new ArrayList<>();
    for (Range r : ranges) {
      if (cut.endMinute() <= r.startMinute() || cut.startMinute() >= r.endMinute()) {
        out.add(r);
        continue;
      }
      if (cut.startMinute() > r.startMinute()) {
        out.add(new Range(r.startMinute(), cut.startMinute()));
      }
      if (cut.endMinute() < r.endMinute()) {
        out.add(new Range(cut.endMinute(), r.endMinute()));
      }
    }
    return out;
  }

  public Optional<List<Range>> rangesOf(DayOfWeek d) {
    return Optional.ofNullable(weekly.get(d));
  }
}
