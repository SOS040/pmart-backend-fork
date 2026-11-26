package com.pmart.order.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * "산 것" 정의 한 곳(2026-11-26 사용자 결정 — 자주 산 상품 ①·고객 구매 지표 O-13 이 같이 쓴다). 확정 주문 중 상태가 {@link
 * #NOT_PURCHASED} 아님, 줄의 남은 수량(주문 수량 − 출고 전 취소) &gt; 0. 횟수는 서로 다른 주문 수(한 주문에 2개 담아도 1번). 반품·환불은 빼지
 * 않는다. review-service 재구매 표시(R4)는 서비스가 달라 자기 표로 따로 센다.
 */
public final class PurchaseCountRules {

  /** 산 것으로 세지 않는 주문 상태. */
  public static final Set<String> NOT_PURCHASED = Set.of("CANCELLED", "EXPIRED");

  /** SQL 조건 조각 — 주문 별칭 {@code o}. */
  public static final String ORDER_PURCHASED = "o.status NOT IN ('CANCELLED', 'EXPIRED')";

  /** SQL 조건 조각 — 줄 별칭 {@code l}. */
  public static final String LINE_PURCHASED = "l.quantity - l.cancelled_before_ship_qty > 0";

  /** SQL 식 — 줄 남은 수량. */
  public static final String LINE_REMAINING_QTY =
      "greatest(l.quantity - l.cancelled_before_ship_qty, 0)";

  /** SQL 식 — 줄 남은 금액(top-products 와 같은 산식, 정수 나눗셈 = 원 미만 버림). */
  public static final String LINE_REMAINING_AMOUNT =
      "CASE WHEN l.quantity > 0 THEN (l.line_amount - l.discount_share) * "
          + LINE_REMAINING_QTY
          + " / l.quantity ELSE 0 END";

  public static final int DEFAULT_WINDOW_DAYS = 180;
  public static final int MAX_WINDOW_DAYS = 365;
  public static final int DEFAULT_MIN_ORDERS = 2;
  public static final int MAX_MIN_ORDERS = 10;
  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 50;

  /** 고객 구매 지표 기간 상한(from·to 포함 날짜 수). */
  public static final int MAX_CUSTOMER_METRICS_DAYS = 366;

  /** O-12 고객별 구매 요약·고객 찾기를 볼 수 있는 역할(직원 고객 조회와 같음, 센터 범위 없음). */
  public static final Set<String> CUSTOMER_STAFF_ROLES = Set.of("CS_AGENT", "OPERATOR_ADMIN");

  /** O-12 한 번에 요약할 고객 수 상한. */
  public static final int MAX_SUMMARY_CUSTOMERS = 50;

  /** O-12 최근 금액 기간(개월) — 오늘(Asia/Seoul) 이만큼 전 날짜 0시부터. */
  public static final int RECENT_AMOUNT_MONTHS = 3;

  /** 고객 찾기 한 쪽(그 밖은 계약 PageSize 1~200). */
  public static final int DEFAULT_PAGE_SIZE = 50;

  /** 마지막 구매 시각·고객 ID 커서. */
  public record Cursor(Instant lastOrderAt, String customerId) {}

  private PurchaseCountRules() {}

  /** O-12 볼 수 있는지. */
  public static boolean customerStaff(java.util.Collection<String> roles) {
    return roles.stream().anyMatch(CUSTOMER_STAFF_ROLES::contains);
  }

  /** 쉼표 구분 고객 ID → 앞뒤 공백 빼고 빈 것 빼고 중복 한 번(순서 유지). 1~50개가 아니면 null. */
  public static @Nullable List<String> customerIds(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (String x : raw.split(",")) {
      String v = x.strip();
      if (!v.isEmpty()) {
        ids.add(v);
      }
    }
    if (ids.isEmpty() || ids.size() > MAX_SUMMARY_CUSTOMERS) {
      return null;
    }
    return new ArrayList<>(ids);
  }

  /** 최근 금액 시작 = 오늘(Asia/Seoul) {@value #RECENT_AMOUNT_MONTHS}개월 전 날짜 0시. */
  public static Instant recentAmountSince(LocalDate todaySeoul) {
    return todaySeoul
        .minusMonths(RECENT_AMOUNT_MONTHS)
        .atStartOfDay(OrderMetricsRules.SEOUL)
        .toInstant();
  }

  /** 마지막 구매일 기간 검사 — 하나는 있어야 하고 from ≤ to. 문제가 있으면 사유, 없으면 null. */
  public static @Nullable String lastOrderRangeProblem(
      @Nullable LocalDate from, @Nullable LocalDate to) {
    if (from == null && to == null) {
      return "lastOrderFrom·lastOrderTo 중 하나는 필요합니다.";
    }
    if (from != null && to != null && from.isAfter(to)) {
      return "lastOrderFrom 은 lastOrderTo 보다 늦을 수 없습니다.";
    }
    return null;
  }

  public static String encodeCursor(Cursor c) {
    String raw =
        c.lastOrderAt().getEpochSecond() + ":" + c.lastOrderAt().getNano() + ":" + c.customerId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** 잘못된 커서면 null. */
  public static @Nullable Cursor decodeCursor(@Nullable String cursor) {
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

  /** 상태 하나가 산 것인지. */
  public static boolean purchased(String status) {
    return !NOT_PURCHASED.contains(status);
  }

  /** 자주 산 상품 조건 검사. 문제가 있으면 사유, 없으면 null. */
  public static @Nullable String frequentProblem(int windowDays, int minOrders, int limit) {
    if (windowDays < 1 || windowDays > MAX_WINDOW_DAYS) {
      return "windowDays 는 1~" + MAX_WINDOW_DAYS + " 이어야 합니다.";
    }
    if (minOrders < 1 || minOrders > MAX_MIN_ORDERS) {
      return "minOrders 는 1~" + MAX_MIN_ORDERS + " 이어야 합니다.";
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      return "limit 은 1~" + MAX_LIMIT + " 이어야 합니다.";
    }
    return null;
  }

  /** 고객 구매 지표 기간 검사(366일). 문제가 있으면 사유, 없으면 null. */
  public static @Nullable String customerMetricsRangeProblem(
      @Nullable LocalDate from, @Nullable LocalDate to) {
    if (from == null || to == null) {
      return "from·to(한국 날짜)가 필요합니다.";
    }
    if (from.isAfter(to)) {
      return "from 은 to 보다 늦을 수 없습니다.";
    }
    if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_CUSTOMER_METRICS_DAYS) {
      return "고객 지표 기간은 " + MAX_CUSTOMER_METRICS_DAYS + "일(포함) 이하여야 합니다.";
    }
    return null;
  }
}
