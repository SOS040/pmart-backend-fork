package com.pmart.order.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.order.domain.OrderMetricsRules;
import com.pmart.order.domain.PurchaseCountRules;
import com.pmart.order.infrastructure.PurchaseHistoryStore;
import com.pmart.order.infrastructure.PurchaseHistoryStore.CustomerCounts;
import com.pmart.order.infrastructure.PurchaseHistoryStore.FrequentRow;
import com.pmart.order.infrastructure.Upstreams;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구매 기록 활용(2026-11-26 사용자 결정): 고객 본인 자주 산 상품(①, 고객앱 검색창 빈칸 제안 ②도 이것)·직원 고객 구매 지표(O-13, 첫 구매 기준). 검색
 * 순위에는 넣지 않는다. "산 것" 정의는 {@link PurchaseCountRules}.
 */
@Service
public class PurchaseHistoryService {

  /** 자주 산 상품 결과 — 센 기간 시작과 줄(사진은 product-content 지금 값, 실패면 null). */
  public record Frequent(Instant from, List<FrequentRow> rows, Map<String, String> images) {}

  private final PurchaseHistoryStore store;
  private final Upstreams upstreams;
  private final Clock clock;

  public PurchaseHistoryService(PurchaseHistoryStore store, Upstreams upstreams, Clock clock) {
    this.store = store;
    this.upstreams = upstreams;
    this.clock = clock;
  }

  /** 고객 토큰만(그 밖 403). 사진 조회는 DB 읽기 뒤(트랜잭션 밖 — 읽기 전용이라 묶지 않는다). */
  public Frequent frequentItems(int windowDays, int minOrders, int limit) {
    String accountId = DraftService.currentCustomer();
    String problem = PurchaseCountRules.frequentProblem(windowDays, minOrders, limit);
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    Instant from = clock.instant().minus(windowDays, ChronoUnit.DAYS);
    List<FrequentRow> rows = store.frequentItems(accountId, from, minOrders, limit);
    List<String> productIds =
        rows.stream().map(FrequentRow::productId).filter(Objects::nonNull).distinct().toList();
    Map<String, String> images =
        productIds.isEmpty() ? Map.of() : upstreams.productImages(productIds);
    return new Frequent(from, rows, images);
  }

  /** 직원 — 권한은 다른 주문 집계와 같다(OPERATOR_ADMIN·그 센터 MFC_SUPERVISOR), 기간 366일. */
  @Transactional(readOnly = true)
  public CustomerCounts customerMetrics(
      @Nullable LocalDate from, @Nullable LocalDate to, @Nullable String mfcId) {
    OrderMetricsService.access(mfcId);
    String problem = PurchaseCountRules.customerMetricsRangeProblem(from, to);
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    OrderMetricsRules.Range r = OrderMetricsRules.range(from, to);
    return store.customerCounts(
        r.from(), r.toExclusive(), mfcId == null || mfcId.isBlank() ? null : mfcId.strip());
  }

  /** O-12 요약 결과 — 요청 순서, 산 것이 없는 고객도 한 줄(0·null). */
  public record Summaries(
      Instant recentSince, List<PurchaseHistoryStore.CustomerSummary> found, List<String> ids) {}

  /** O-12 찾기 결과. */
  public record LastOrders(
      List<PurchaseHistoryStore.LastOrder> items, @Nullable String nextCursor) {}

  /** O-12: 직원(CS_AGENT·OPERATOR_ADMIN) — 고객별 구매 요약, 최대 50명. */
  @Transactional(readOnly = true)
  public Summaries customerSummaries(@Nullable String customerIds) {
    customerStaff();
    List<String> ids = PurchaseCountRules.customerIds(customerIds);
    if (ids == null) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "customerIds 는 1~" + PurchaseCountRules.MAX_SUMMARY_CUSTOMERS + "개여야 합니다.");
    }
    Instant since =
        PurchaseCountRules.recentAmountSince(
            LocalDate.now(clock.withZone(OrderMetricsRules.SEOUL)));
    return new Summaries(since, store.customerSummaries(ids, since), ids);
  }

  /** O-12: 직원 — 마지막 구매일(한국 날짜, 둘 다 포함) 기간으로 고객 찾기. */
  @Transactional(readOnly = true)
  public LastOrders customersByLastOrder(
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable Integer size,
      @Nullable String cursor) {
    customerStaff();
    String problem = PurchaseCountRules.lastOrderRangeProblem(from, to);
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    PurchaseCountRules.Cursor after = PurchaseCountRules.decodeCursor(cursor);
    if (cursor != null && !cursor.isBlank() && after == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
    }
    int n = size == null ? PurchaseCountRules.DEFAULT_PAGE_SIZE : size;
    if (n < 1 || n > 200) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "size 는 1~200 이어야 합니다.");
    }
    List<PurchaseHistoryStore.LastOrder> rows =
        store.customersByLastOrder(
            from == null ? null : from.atStartOfDay(OrderMetricsRules.SEOUL).toInstant(),
            to == null ? null : to.plusDays(1).atStartOfDay(OrderMetricsRules.SEOUL).toInstant(),
            after == null ? null : after.lastOrderAt(),
            after == null ? null : after.customerId(),
            n + 1);
    boolean more = rows.size() > n;
    List<PurchaseHistoryStore.LastOrder> page = more ? rows.subList(0, n) : rows;
    String next =
        more
            ? PurchaseCountRules.encodeCursor(
                new PurchaseCountRules.Cursor(
                    page.get(n - 1).lastOrderAt(), page.get(n - 1).customerId()))
            : null;
    return new LastOrders(List.copyOf(page), next);
  }

  private static void customerStaff() {
    boolean ok =
        com.pmart.common.security.PmartPrincipal.current()
            .filter(
                p -> p.type() == com.pmart.common.security.PmartPrincipal.PrincipalType.OPERATOR)
            .map(p -> PurchaseCountRules.customerStaff(p.roles()))
            .orElse(false);
    if (!ok) {
      throw new PmartException(ErrorCode.FORBIDDEN, "고객 구매 요약 권한이 없습니다(CS_AGENT·OPERATOR_ADMIN).");
    }
  }
}
