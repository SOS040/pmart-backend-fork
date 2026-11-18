package com.pmart.order.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.order.domain.OrderMetricsRules;
import com.pmart.order.infrastructure.OrderMetricsStore;
import com.pmart.order.infrastructure.OrderMetricsStore.ProductRow;
import com.pmart.order.infrastructure.OrderMetricsStore.StatusRow;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직원용 기간 집계(2026-11-18 사용자 결정, O-8 — 백오피스 대시보드 D2·D3). OPERATOR 토큰 + 역할 OPERATOR_ADMIN 만. 기준 날짜 =
 * 주문 확정 시각의 한국 날짜. 환불은 빼지 않는다(claim-service 가 따로). order 원장만 읽는다(다른 서비스 호출 없음).
 */
@Service
public class OrderMetricsService {

  /** 상태별 주문 수(0 인 상태 없음) + 결제 승인 금액 합·주문 수. */
  public record Summary(
      int orderCount, List<StatusRow> byStatus, long approvedAmount, int approvedOrderCount) {}

  private final OrderMetricsStore store;

  public OrderMetricsService(OrderMetricsStore store) {
    this.store = store;
  }

  @Transactional(readOnly = true)
  public Summary summary(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable String mfcId) {
    OrderMetricsRules.Range r = range(from, to);
    List<StatusRow> rows = store.statusCounts(r.from(), r.toExclusive(), blankToNull(mfcId));
    int count = rows.stream().mapToInt(StatusRow::count).sum();
    long amount = rows.stream().mapToLong(StatusRow::approvedAmount).sum();
    return new Summary(count, rows, amount, count);
  }

  @Transactional(readOnly = true)
  public List<ProductRow> topProducts(
      @Nullable LocalDate from, @Nullable LocalDate to, @Nullable String mfcId, int limit) {
    OrderMetricsRules.Range r = range(from, to);
    String problem = OrderMetricsRules.limitProblem(limit);
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    return store.topProducts(r.from(), r.toExclusive(), blankToNull(mfcId), limit);
  }

  /** 권한 먼저(403), 그다음 기간(400). */
  private static OrderMetricsRules.Range range(@Nullable LocalDate from, @Nullable LocalDate to) {
    PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .filter(p -> OrderMetricsRules.allowed(p.roles()))
        .orElseThrow(
            () -> new PmartException(ErrorCode.FORBIDDEN, "주문 집계 권한이 없습니다(OPERATOR_ADMIN)."));
    String problem = OrderMetricsRules.rangeProblem(from, to);
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    return OrderMetricsRules.range(from, to);
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
