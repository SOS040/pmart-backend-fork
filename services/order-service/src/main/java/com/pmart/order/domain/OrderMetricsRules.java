package com.pmart.order.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

/**
 * 직원용 기간 집계 규칙(2026-11-18, O-8 — 백오피스 대시보드 D2·D3). 기준 날짜 = 주문 확정 시각의 한국 날짜(ADR-0010), from·to 둘 다
 * 포함·필수, 93일 이하. 볼 수 있는 역할은 OPERATOR_ADMIN 하나.
 */
public final class OrderMetricsRules {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  /** 집계를 볼 수 있는 역할(OPERATOR 토큰). */
  public static final String ROLE = "OPERATOR_ADMIN";

  /** 기간 상한(from·to 포함 날짜 수). */
  public static final int MAX_DAYS = 93;

  public static final int DEFAULT_LIMIT = 10;
  public static final int MAX_LIMIT = 50;

  /** 확정 시각 범위: from 이상, toExclusive 미만(UTC Instant). */
  public record Range(Instant from, Instant toExclusive) {}

  private OrderMetricsRules() {}

  public static boolean allowed(Collection<String> roles) {
    return roles.contains(ROLE);
  }

  /** 기간 검사. 문제가 있으면 사유, 없으면 null. */
  public static @Nullable String rangeProblem(@Nullable LocalDate from, @Nullable LocalDate to) {
    if (from == null || to == null) {
      return "from·to(한국 날짜)가 필요합니다.";
    }
    if (from.isAfter(to)) {
      return "from 은 to 보다 늦을 수 없습니다.";
    }
    if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
      return "집계 기간은 " + MAX_DAYS + "일(포함) 이하여야 합니다.";
    }
    return null;
  }

  /** 한국 날짜 from·to(포함) → 확정 시각 범위. from 00:00 KST 이상, to 다음 날 00:00 KST 미만. */
  public static Range range(LocalDate from, LocalDate to) {
    return new Range(
        from.atStartOfDay(SEOUL).toInstant(), to.plusDays(1).atStartOfDay(SEOUL).toInstant());
  }

  /** 상위 N 검사. 문제가 있으면 사유, 없으면 null. */
  public static @Nullable String limitProblem(int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      return "limit 은 1~" + MAX_LIMIT + " 이어야 합니다.";
    }
    return null;
  }
}
