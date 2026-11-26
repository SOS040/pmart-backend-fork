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

  /** 0.20.0: 상위 상품 줄 + 대표 카테고리. */
  public record TopProduct(
      ProductRow row, @Nullable String categoryId, @Nullable String categoryName) {}

  /** 0.20.0 O-11: 추이 한 칸 + WEEKDAY_HOUR 평균의 분모. */
  public record SeriesPoint(OrderMetricsStore.SeriesRow row, @Nullable Integer days) {}

  private final OrderMetricsStore store;
  private final ProductCategories categories;

  public OrderMetricsService(OrderMetricsStore store, ProductCategories categories) {
    this.store = store;
    this.categories = categories;
  }

  @Transactional(readOnly = true)
  public Summary summary(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable String mfcId) {
    OrderMetricsRules.Range r = range(from, to, mfcId);
    List<StatusRow> rows = store.statusCounts(r.from(), r.toExclusive(), blankToNull(mfcId));
    int count = rows.stream().mapToInt(StatusRow::count).sum();
    long amount = rows.stream().mapToLong(StatusRow::approvedAmount).sum();
    return new Summary(count, rows, amount, count);
  }

  @Transactional(readOnly = true)
  public List<TopProduct> topProducts(
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable String mfcId,
      @Nullable String categoryId,
      int limit) {
    OrderMetricsRules.Range r = range(from, to, mfcId);
    String problem = OrderMetricsRules.limitProblem(limit);
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    ProductCategories.Snapshot cat = categories.current();
    String category = blankToNull(categoryId);
    List<ProductRow> rows =
        store.topProducts(
            r.from(),
            r.toExclusive(),
            blankToNull(mfcId),
            category == null ? null : cat.productsIn(category),
            limit);
    return rows.stream()
        .map(
            x -> {
              String c = x.productId() == null ? null : cat.categoryOfProduct().get(x.productId());
              return new TopProduct(x, c, categories.nameOf(cat, c));
            })
        .toList();
  }

  /** 0.20.0 O-11: 시간·날짜별, 요일×시간대 추이. */
  @Transactional(readOnly = true)
  public List<SeriesPoint> series(
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable String mfcId,
      OrderMetricsRules.Bucket bucket,
      @Nullable String sku,
      @Nullable String categoryId) {
    access(mfcId);
    String problem = OrderMetricsRules.seriesRangeProblem(from, to, bucket);
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    OrderMetricsRules.Range r = OrderMetricsRules.range(from, to);
    String category = blankToNull(categoryId);
    java.util.Set<String> products =
        category == null ? null : categories.current().productsIn(category);
    return store
        .series(
            r.from(),
            r.toExclusive(),
            blankToNull(mfcId),
            bucket.name(),
            blankToNull(sku),
            products)
        .stream()
        .map(
            x ->
                new SeriesPoint(
                    x,
                    bucket == OrderMetricsRules.Bucket.WEEKDAY_HOUR
                        ? OrderMetricsRules.weekdayDays(from, to, x.weekday())
                        : null))
        .toList();
  }

  /** 권한 먼저(403·센터 필요 400), 그다음 기간(400). */
  private static OrderMetricsRules.Range range(
      @Nullable LocalDate from, @Nullable LocalDate to, @Nullable String mfcId) {
    access(mfcId);
    String problem = OrderMetricsRules.rangeProblem(from, to);
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    return OrderMetricsRules.range(from, to);
  }

  /**
   * 0.20.0 O-11: OPERATOR_ADMIN 모든 센터, MFC_SUPERVISOR 는 mfcId 필수·토큰 센터만. 0.24.0 고객 구매 지표({@link
   * PurchaseHistoryService})도 같이 쓴다.
   */
  static void access(@Nullable String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "주문 집계 권한이 없습니다."));
    switch (OrderMetricsRules.access(p.roles(), p.centerScope(), mfcId)) {
      case ALLOWED -> {}
      case CENTER_REQUIRED ->
          throw new PmartException(ErrorCode.VALIDATION_FAILED, "센터 관리자는 mfcId(자기 센터)가 필요합니다.");
      case DENIED ->
          throw new PmartException(
              ErrorCode.FORBIDDEN, "주문 집계 권한이 없습니다(OPERATOR_ADMIN 또는 그 센터 MFC_SUPERVISOR).");
    }
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
