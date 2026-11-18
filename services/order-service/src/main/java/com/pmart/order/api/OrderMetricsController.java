package com.pmart.order.api;

import com.pmart.order.api.generated.OrderMetricsApi;
import com.pmart.order.api.generated.model.OrderMetricsSummary;
import com.pmart.order.api.generated.model.OrderMetricsSummaryStatusCountsInner;
import com.pmart.order.api.generated.model.OrderStatus;
import com.pmart.order.api.generated.model.OrderTopProductMetrics;
import com.pmart.order.api.generated.model.OrderTopProductMetricsItemsInner;
import com.pmart.order.application.OrderMetricsService;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 OrderMetricsApi — 직원용 기간 집계(0.13.0, O-8). 경로는 글자 그대로의 {@code /orders/metrics/...} 라 {@code
 * /orders/{orderId}} 보다 먼저 맞는다(Spring 은 변수 없는 경로를 앞세운다). 권한·기간은 {@link OrderMetricsService}, 여기서는
 * 변환만.
 */
@RestController
public class OrderMetricsController implements OrderMetricsApi {

  private final OrderMetricsService service;

  public OrderMetricsController(OrderMetricsService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<OrderMetricsSummary> getOrderMetricsSummary(
      LocalDate from, LocalDate to, @Nullable String mfcId) {
    OrderMetricsService.Summary s = service.summary(from, to, mfcId);
    return ResponseEntity.ok(
        new OrderMetricsSummary()
            .from(from)
            .to(to)
            .mfcId(blankToNull(mfcId))
            .orderCount(s.orderCount())
            .statusCounts(
                s.byStatus().stream()
                    .map(
                        r ->
                            new OrderMetricsSummaryStatusCountsInner()
                                .status(OrderStatus.fromValue(r.status()))
                                .count(r.count()))
                    .toList())
            .approvedAmount(s.approvedAmount())
            .approvedOrderCount(s.approvedOrderCount()));
  }

  @Override
  public ResponseEntity<OrderTopProductMetrics> getTopProductMetrics(
      LocalDate from, LocalDate to, @Nullable String mfcId, Integer limit) {
    return ResponseEntity.ok(
        new OrderTopProductMetrics()
            .from(from)
            .to(to)
            .mfcId(blankToNull(mfcId))
            .limit(limit)
            .items(
                service.topProducts(from, to, mfcId, limit).stream()
                    .map(
                        r ->
                            new OrderTopProductMetricsItemsInner()
                                .sku(r.sku())
                                .productId(r.productId())
                                .qty(r.qty())
                                .amount(r.amount()))
                    .toList()));
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
