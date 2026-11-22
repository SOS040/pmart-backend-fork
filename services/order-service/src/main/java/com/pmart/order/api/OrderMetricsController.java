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
      LocalDate from,
      LocalDate to,
      @Nullable String mfcId,
      @Nullable String categoryId,
      Integer limit) {
    return ResponseEntity.ok(
        new OrderTopProductMetrics()
            .from(from)
            .to(to)
            .mfcId(blankToNull(mfcId))
            .limit(limit)
            .items(
                service.topProducts(from, to, mfcId, categoryId, limit).stream()
                    .map(
                        t ->
                            new OrderTopProductMetricsItemsInner()
                                .sku(t.row().sku())
                                .productId(t.row().productId())
                                .qty(t.row().qty())
                                .amount(t.row().amount())
                                .categoryId(t.categoryId())
                                .categoryName(t.categoryName()))
                    .toList()));
  }

  /** 0.20.0 O-11 — 시간·날짜별, 요일×시간대. */
  @Override
  public ResponseEntity<com.pmart.order.api.generated.model.OrderMetricsSeries>
      getOrderMetricsSeries(
          LocalDate from,
          LocalDate to,
          String bucket,
          @Nullable String mfcId,
          @Nullable String sku,
          @Nullable String categoryId) {
    com.pmart.order.domain.OrderMetricsRules.Bucket b;
    try {
      b = com.pmart.order.domain.OrderMetricsRules.Bucket.valueOf(bucket);
    } catch (IllegalArgumentException e) {
      throw new com.pmart.common.web.PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "bucket 은 HOUR·DAY·WEEKDAY_HOUR 입니다.");
    }
    java.time.ZoneId seoul = com.pmart.order.domain.OrderMetricsRules.SEOUL;
    return ResponseEntity.ok(
        new com.pmart.order.api.generated.model.OrderMetricsSeries()
            .from(from)
            .to(to)
            .mfcId(blankToNull(mfcId))
            .bucket(
                com.pmart.order.api.generated.model.OrderMetricsSeries.BucketEnum.fromValue(bucket))
            .sku(blankToNull(sku))
            .categoryId(blankToNull(categoryId))
            .items(
                service.series(from, to, mfcId, b, sku, categoryId).stream()
                    .map(
                        p -> {
                          var r = p.row();
                          var item =
                              new com.pmart.order.api.generated.model.OrderMetricsSeriesItemsInner()
                                  .orderCount(r.orderCount())
                                  .approvedAmount(r.approvedAmount())
                                  .qty(Math.toIntExact(r.qty()))
                                  .lineAmount(r.lineAmount());
                          if (p.days() == null) {
                            item.start(
                                r.start() == null
                                    ? null
                                    : r.start().atZone(seoul).toOffsetDateTime());
                          } else {
                            int d = p.days();
                            item.weekday(r.weekday())
                                .hour(r.hour())
                                .days(d)
                                .avgOrderCount(
                                    d == 0
                                        ? null
                                        : java.math.BigDecimal.valueOf(r.orderCount())
                                            .divide(
                                                java.math.BigDecimal.valueOf(d),
                                                2,
                                                java.math.RoundingMode.HALF_UP))
                                .avgLineAmount(
                                    d == 0
                                        ? null
                                        : java.math.BigDecimal.valueOf(r.lineAmount())
                                            .divide(
                                                java.math.BigDecimal.valueOf(d),
                                                2,
                                                java.math.RoundingMode.HALF_UP));
                          }
                          return item;
                        })
                    .toList()));
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
