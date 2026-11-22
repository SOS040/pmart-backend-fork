package com.pmart.claim.application;

import com.pmart.claim.domain.RefundMetricsRules;
import com.pmart.claim.domain.RefundMetricsRules.Bucket;
import com.pmart.claim.infrastructure.RefundMetricsStore;
import com.pmart.claim.infrastructure.RefundMetricsStore.ReasonRow;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직원용 기간 환불 집계(2026-11-18 사용자 결정, CL-3 — 백오피스 대시보드 D2, 환불은 주문 매출에서 빼지 않고 따로). OPERATOR 토큰 + 역할
 * OPERATOR_ADMIN 만. 기준 = 환불 완료 시각의 한국 날짜, 완료(COMPLETED)만, 센터 거르기 없음.
 */
@Service
public class RefundMetricsService {

  /** 묶음 하나. */
  public record Line(Bucket bucket, long amount, int count) {}

  /** 합 + 묶음별(늘 {@link Bucket} 전부, 그 순서). */
  public record Result(long totalAmount, int totalCount, List<Line> byBucket) {}

  private final RefundMetricsStore store;

  public RefundMetricsService(RefundMetricsStore store) {
    this.store = store;
  }

  @Transactional(readOnly = true)
  public Result refunds(@Nullable LocalDate from, @Nullable LocalDate to) {
    return refunds(from, to, null);
  }

  /** 0.13.0 CL-8: {@code mfcId} 가 있으면 그 센터 주문의 환불만. */
  @Transactional(readOnly = true)
  public Result refunds(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable String mfcId) {
    PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .filter(p -> RefundMetricsRules.allowed(p.roles()))
        .orElseThrow(
            () -> new PmartException(ErrorCode.FORBIDDEN, "환불 집계 권한이 없습니다(OPERATOR_ADMIN)."));
    String problem = RefundMetricsRules.rangeProblem(from, to);
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    RefundMetricsRules.Range r = RefundMetricsRules.range(from, to);
    return group(
        store.completedByReason(
            r.from(), r.toExclusive(), mfcId == null || mfcId.isBlank() ? null : mfcId));
  }

  /** 근거별 행 → 묶음별 합(없는 묶음은 0). */
  static Result group(List<ReasonRow> rows) {
    Map<Bucket, long[]> acc = new EnumMap<>(Bucket.class);
    for (Bucket b : Bucket.values()) {
      acc.put(b, new long[2]);
    }
    for (ReasonRow row : rows) {
      long[] a = acc.get(RefundMetricsRules.bucket(row.reason()));
      a[0] += row.amount();
      a[1] += row.count();
    }
    List<Line> lines = new ArrayList<>();
    long total = 0;
    int count = 0;
    for (Bucket b : Bucket.values()) {
      long[] a = acc.get(b);
      lines.add(new Line(b, a[0], (int) a[1]));
      total += a[0];
      count += (int) a[1];
    }
    return new Result(total, count, lines);
  }
}
