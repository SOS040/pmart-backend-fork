package com.pmart.order.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 고객 주문 내역 규칙(2026-11-18, 계약 0.12.0 — 고객 앱 주문 내역, 사용자 "A해줘"). 목록 기간(Asia/Seoul 날짜, 둘 다 포함, 기본 최근
 * 3개월), 상품명 검색어 정리, 숨김(고객의 "삭제") 가능 상태.
 */
public final class OrderHistoryRules {

  /** from·to 둘 다 없을 때 보는 기간(오늘에서 이만큼 전 날짜 0시부터). */
  public static final int DEFAULT_MONTHS = 3;

  /** 검색어 상한(계약 maxLength 와 같다). */
  public static final int MAX_QUERY_LENGTH = 100;

  /** 끝난 주문 — 이 상태만 숨길 수 있다. 진행 중 주문은 숨기지 않는다(배송·클레임 중 고객이 찾지 못하게 되지 않도록). */
  public static final Set<String> FINISHED = Set.of("COMPLETED", "CANCELLED", "EXPIRED");

  private OrderHistoryRules() {}

  /** 확정 시각 범위 [from, toExclusive). null 은 그쪽으로 자르지 않음. */
  public record Period(@Nullable Instant from, @Nullable Instant toExclusive) {}

  /**
   * 목록 기간. 둘 다 없으면 최근 {@value #DEFAULT_MONTHS}개월(오늘 기준 Asia/Seoul). to 는 그 날을 포함(다음 날 0시 미만).
   *
   * @throws IllegalArgumentException from 이 to 보다 뒤
   */
  public static Period period(
      @Nullable LocalDate from, @Nullable LocalDate to, LocalDate todaySeoul) {
    if (from != null && to != null && from.isAfter(to)) {
      throw new IllegalArgumentException("from 은 to 보다 뒤일 수 없습니다.");
    }
    if (from == null && to == null) {
      return new Period(
          todaySeoul.minusMonths(DEFAULT_MONTHS).atStartOfDay(OrderRules.SEOUL).toInstant(), null);
    }
    return new Period(
        from == null ? null : from.atStartOfDay(OrderRules.SEOUL).toInstant(),
        to == null ? null : to.plusDays(1).atStartOfDay(OrderRules.SEOUL).toInstant());
  }

  /** 검색어 정리 — 앞뒤 공백 제거, 비면 null(검색 안 함). 소문자로(대소문자 무시 비교). */
  public static @Nullable String query(@Nullable String q) {
    if (q == null) {
      return null;
    }
    String t = q.strip();
    if (t.isEmpty()) {
      return null;
    }
    if (t.length() > MAX_QUERY_LENGTH) {
      throw new IllegalArgumentException("q 는 " + MAX_QUERY_LENGTH + "자 이하여야 합니다.");
    }
    return t.toLowerCase(Locale.ROOT);
  }

  /** LIKE 패턴(부분 일치). 와일드카드 문자(\ % _)는 글자 그대로 찾도록 escape 한다(ESCAPE '\'). */
  public static String likePattern(String q) {
    return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  public static boolean hideable(String status) {
    return FINISHED.contains(status);
  }
}
