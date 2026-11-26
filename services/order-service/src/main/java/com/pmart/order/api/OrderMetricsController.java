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
  private final com.pmart.order.application.PurchaseHistoryService purchases;

  public OrderMetricsController(
      OrderMetricsService service, com.pmart.order.application.PurchaseHistoryService purchases) {
    this.service = service;
    this.purchases = purchases;
  }

  /** 0.25.0 O-12 고객별 구매 요약 — 요청 순서, 산 것이 없는 고객도 0. */
  @Override
  public ResponseEntity<com.pmart.order.api.generated.model.CustomerPurchaseSummaries>
      getCustomerPurchaseSummaries(String customerIds) {
    var r = purchases.customerSummaries(customerIds);
    java.util.Map<String, com.pmart.order.infrastructure.PurchaseHistoryStore.CustomerSummary> by =
        new java.util.HashMap<>();
    r.found().forEach(x -> by.put(x.customerId(), x));
    return ResponseEntity.ok(
        new com.pmart.order.api.generated.model.CustomerPurchaseSummaries()
            .amountSince(utc(r.recentSince()))
            .items(
                r.ids().stream()
                    .map(
                        id -> {
                          var x = by.get(id);
                          var item =
                              new com.pmart.order.api.generated.model
                                      .CustomerPurchaseSummariesItemsInner()
                                  .customerId(id);
                          if (x == null) {
                            return item.orderCount(0).totalAmount(0L).amount3m(0L);
                          }
                          return item.orderCount(x.orderCount())
                              .totalAmount(x.totalAmount())
                              .amount3m(x.recentAmount())
                              .firstOrderAt(utc(x.firstOrderAt()))
                              .lastOrderAt(utc(x.lastOrderAt()));
                        })
                    .toList()));
  }

  /** 0.25.0 O-12 마지막 구매일 기간으로 고객 찾기. */
  @Override
  public ResponseEntity<com.pmart.order.api.generated.model.FindCustomersByLastOrder200Response>
      findCustomersByLastOrder(
          @Nullable LocalDate lastOrderFrom,
          @Nullable LocalDate lastOrderTo,
          @Nullable Integer size,
          @Nullable String cursor) {
    var r = purchases.customersByLastOrder(lastOrderFrom, lastOrderTo, size, cursor);
    return ResponseEntity.ok(
        new com.pmart.order.api.generated.model.FindCustomersByLastOrder200Response()
            .items(
                r.items().stream()
                    .map(
                        x ->
                            new com.pmart.order.api.generated.model
                                    .FindCustomersByLastOrder200ResponseItemsInner()
                                .customerId(x.customerId())
                                .lastOrderAt(utc(x.lastOrderAt())))
                    .toList())
            .page(
                new com.pmart.order.api.generated.model.ListOrders200ResponsePage()
                    .nextCursor(r.nextCursor())
                    .size(size == null ? 50 : size)));
  }

  private static java.time.OffsetDateTime utc(java.time.Instant i) {
    return java.time.OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC);
  }

  /** 0.24.0 O-13 고객 구매 지표(첫 구매 기준) — 분자·분모만. */
  @Override
  public ResponseEntity<com.pmart.order.api.generated.model.CustomerPurchaseMetrics>
      getCustomerPurchaseMetrics(LocalDate from, LocalDate to, @Nullable String mfcId) {
    var c = purchases.customerMetrics(from, to, mfcId);
    return ResponseEntity.ok(
        new com.pmart.order.api.generated.model.CustomerPurchaseMetrics()
            .from(from)
            .to(to)
            .mfcId(blankToNull(mfcId))
            .customerCount(c.customerCount())
            .repeatCustomerCount(c.repeatCustomerCount())
            .oneTimeCustomerCount(c.oneTimeCustomerCount())
            .orderCount(c.orderCount())
            .purchaseAmount(c.purchaseAmount()));
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
