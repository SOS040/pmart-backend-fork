package com.pmart.delivery.capacity.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 예약 배송 슬롯 규칙(Commerce 9장).
 *
 * <ul>
 *   <li>오늘/내일(Asia/Seoul)의 1시간 도착 시간대. 센터 고객 주문 운영 구간(center-master CUSTOMER_ORDER) 안에 온전히 들어가는 것만.
 *   <li>[확정 정책] 00:00~08:00 에는 실제 배송을 하지 않는다 — 운영 구간이 그 시간을 포함해도 08시 전 시간대는 만들지 않는다(07시 도착 슬롯 없음).
 *   <li>정원은 승인 정책값. 없으면 CONFIG_REQUIRED(0·무제한 아님). 선택 마감 선행시간은 정책이 없어 시간대 시작 시각에 마감한다.
 * </ul>
 */
public final class SlotRules {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  /** 실제 배송 금지 구간 끝(08:00, 분). */
  public static final int DELIVERY_BAN_END_MINUTE = 8 * 60;

  public record Window(LocalDate date, int startMinute, int endMinute) {
    public String start() {
      return text(startMinute);
    }

    public String end() {
      return text(endMinute);
    }

    public Instant startAt() {
      return date.atStartOfDay(SEOUL).plusMinutes(startMinute).toInstant();
    }
  }

  private SlotRules() {}

  /** 운영 구간들 → 1시간 도착 시간대(정시 시작). */
  public static List<Window> windows(LocalDate date, List<int[]> openRanges) {
    List<Window> out = new ArrayList<>();
    for (int[] r : openRanges) {
      int from = Math.max(r[0], DELIVERY_BAN_END_MINUTE);
      int firstHour = (from + 59) / 60;
      for (int h = firstHour; (h + 1) * 60 <= r[1]; h++) {
        out.add(new Window(date, h * 60, (h + 1) * 60));
      }
    }
    return out;
  }

  /** 선택 가능 여부. capacity null = 정책 미설정. */
  public static String availability(
      @Nullable Integer capacity, int held, int confirmed, Instant cutoffAt, Instant now) {
    if (capacity == null) {
      return "CONFIG_REQUIRED";
    }
    if (!now.isBefore(cutoffAt)) {
      return "CLOSED";
    }
    return capacity - held - confirmed <= 0 ? "FULL" : "AVAILABLE";
  }

  /** 슬롯 식별자(조회·점유 공통). */
  public static String slotId(String mfcId, Window w) {
    return mfcId + ":" + w.date() + ":" + w.start();
  }

  /** 예약 가능 날짜: 오늘·내일(Asia/Seoul). */
  public static boolean bookableDate(LocalDate date, Instant now) {
    LocalDate today = LocalDate.ofInstant(now, SEOUL);
    return !date.isBefore(today) && !date.isAfter(today.plusDays(1));
  }

  /**
   * 점유 요청 시간대가 선택 가능한지 — 조회 목록과 같은 규칙: 운영 구간 안의 정시 1시간 시간대(08시 전 없음)이고 선택 마감(시작 시각) 전. 날짜 범위·정원은 따로
   * 본다.
   */
  public static boolean selectable(Window w, List<int[]> openRanges, Instant now) {
    return now.isBefore(w.startAt()) && windows(w.date(), openRanges).contains(w);
  }

  /** "HH:mm" 시작·끝 → 시간대. 형식이 틀리면 IllegalArgumentException(1시간·정시 여부는 {@link #selectable}). */
  public static Window parseWindow(LocalDate date, String start, String end) {
    if (!isTime(start) || !isTime(end) || "24:00".equals(start)) {
      throw new IllegalArgumentException("시간대는 HH:mm 형식이어야 합니다: " + start + "~" + end);
    }
    return new Window(date, minutes(start), minutes(end));
  }

  private static boolean isTime(@Nullable String hhmm) {
    return hhmm != null && hhmm.matches("([01]\\d|2[0-3]):[0-5]\\d|24:00");
  }

  /** "HH:mm" → 분(끝 24:00 = 1440). */
  public static int minutes(String hhmm) {
    if ("24:00".equals(hhmm)) {
      return 1440;
    }
    String[] p = hhmm.split(":");
    return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
  }

  static String text(int minute) {
    return minute == 1440 ? "24:00" : String.format("%02d:%02d", minute / 60, minute % 60);
  }
}
