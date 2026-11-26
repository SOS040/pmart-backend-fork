package com.pmart.order.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

  private PurchaseCountRules() {}

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
