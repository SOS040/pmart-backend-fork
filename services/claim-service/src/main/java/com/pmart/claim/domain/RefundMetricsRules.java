package com.pmart.claim.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

/**
 * 직원용 기간 환불 집계 규칙(2026-11-18, CL-3 — 백오피스 대시보드 D2). 기준 날짜 = 환불 완료 시각의 한국 날짜(ADR-0010), from·to 둘 다
 * 포함·필수, 93일 이하. 볼 수 있는 역할은 OPERATOR_ADMIN 하나. 센터 거르기 없음(사용자 동의 — 취소 클레임에는 센터가 없다).
 */
public final class RefundMetricsRules {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  /** 집계를 볼 수 있는 역할(OPERATOR 토큰). */
  public static final String ROLE = "OPERATOR_ADMIN";

  /** 기간 상한(from·to 포함 날짜 수). */
  public static final int MAX_DAYS = 93;

  /** 완료 시각 범위: from 이상, toExclusive 미만(UTC Instant). */
  public record Range(Instant from, Instant toExclusive) {}

  /** 환불 근거(refund_intent.reason) 묶음. 이 순서로 응답한다. */
  public enum Bucket {
    /** CANCEL_BEFORE_SHIP — 출고 전 전체 취소(고객·고객센터·시스템). */
    CANCEL,
    /** OUT_OF_STOCK — 결품 부분 환불. */
    SHORTAGE,
    /** RETURN — 반품 검수 환불. */
    RETURN,
    /** RETURN_FEE — 반품 취소 때 돌려준 회수비(회수비 결제의 취소). */
    RETURN_FEE,
    /** 그 밖의 근거. */
    OTHER
  }

  private RefundMetricsRules() {}

  public static boolean allowed(Collection<String> roles) {
    return roles.contains(ROLE);
  }

  public static Bucket bucket(@Nullable String refundReason) {
    if (refundReason == null) {
      return Bucket.OTHER;
    }
    return switch (refundReason) {
      case "CANCEL_BEFORE_SHIP" -> Bucket.CANCEL;
      case "OUT_OF_STOCK" -> Bucket.SHORTAGE;
      case "RETURN" -> Bucket.RETURN;
      case ReturnRules.RETURN_FEE_REFUND_REASON -> Bucket.RETURN_FEE;
      default -> Bucket.OTHER;
    };
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

  /** 한국 날짜 from·to(포함) → 완료 시각 범위. from 00:00 KST 이상, to 다음 날 00:00 KST 미만. */
  public static Range range(LocalDate from, LocalDate to) {
    return new Range(
        from.atStartOfDay(SEOUL).toInstant(), to.plusDays(1).atStartOfDay(SEOUL).toInstant());
  }
}
