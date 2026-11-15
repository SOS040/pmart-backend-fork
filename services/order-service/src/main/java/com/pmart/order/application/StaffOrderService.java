package com.pmart.order.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.order.domain.ShippingAddress;
import com.pmart.order.domain.StaffOrderRules;
import com.pmart.order.infrastructure.OrderStore;
import com.pmart.order.infrastructure.OrderStore.LineRow;
import com.pmart.order.infrastructure.OrderStore.OrderRow;
import com.pmart.order.infrastructure.OrderStore.StaffCriteria;
import com.pmart.order.infrastructure.OrderStore.StaffSummary;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직원용 주문 검색·상세(2026-11-15 사용자 결정, O-6 — 백오피스 고객센터·라이더 운영). OPERATOR 토큰 + 역할
 * OPERATOR_ADMIN·CS_AGENT·RIDER_OPS({@link StaffOrderRules#ROLES}) 만. order 원장만 읽는다(다른 서비스 호출 없음,
 * 읽기 전용).
 *
 * <p>검색 조건(특히 연락처)·응답의 배송지는 개인정보라 로그에 남기지 않는다.
 */
@Service
public class StaffOrderService {

  /** 상세: 주문 + 주문행 + 배송지 사본(가리지 않음) + 이행 확인 필요 기록. */
  public record Detail(
      OrderRow order,
      List<LineRow> lines,
      @Nullable ShippingAddress shipping,
      List<FulfillmentIssueService.Issue> fulfillmentIssues) {}

  /** 검색 결과 한 쪽. receiverPhoneMasked 는 이미 가린 값. */
  public record Row(StaffSummary summary, @Nullable String receiverPhoneMasked) {}

  public record Page(List<Row> items, int page, int size, boolean hasNext) {}

  /** 검색어. createdFrom·createdTo 는 확정 시각 기준. */
  public record Query(
      @Nullable String orderId,
      @Nullable String customerId,
      @Nullable String status,
      @Nullable String mfcId,
      @Nullable String receiverPhone,
      java.time.@Nullable Instant createdFrom,
      java.time.@Nullable Instant createdTo,
      int page,
      int size) {}

  private final OrderStore orders;
  private final FulfillmentIssueService issues;

  public StaffOrderService(OrderStore orders, FulfillmentIssueService issues) {
    this.orders = orders;
    this.issues = issues;
  }

  @Transactional(readOnly = true)
  public Page search(Query q) {
    requireStaff();
    String orderId = blankToNull(q.orderId());
    String customerId = blankToNull(q.customerId());
    String problem =
        StaffOrderRules.rangeProblem(
            orderId != null || customerId != null, q.createdFrom(), q.createdTo());
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    String phone = null;
    String rawPhone = blankToNull(q.receiverPhone());
    if (rawPhone != null) {
      phone = StaffOrderRules.normalizePhone(rawPhone);
      if (phone == null) {
        // 검색어(연락처)를 메시지에 되돌려 싣지 않는다
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "receiverPhone 은 숫자 9~11자리여야 합니다.");
      }
    }
    var criteria =
        new StaffCriteria(
            orderId,
            customerId,
            q.status(),
            blankToNull(q.mfcId()),
            phone,
            q.createdFrom(),
            q.createdTo());
    List<StaffSummary> rows = orders.staffSearch(criteria, q.page() * q.size(), q.size() + 1);
    boolean hasNext = rows.size() > q.size();
    if (hasNext) {
      rows = rows.subList(0, q.size());
    }
    return new Page(
        rows.stream().map(s -> new Row(s, StaffOrderRules.maskPhone(s.receiverPhone()))).toList(),
        q.page(),
        q.size(),
        hasNext);
  }

  @Transactional(readOnly = true)
  public Detail get(String orderId) {
    requireStaff();
    OrderRow o =
        orders
            .order(orderId)
            .orElseThrow(() -> PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다."));
    return new Detail(
        o, orders.lines(orderId), orders.shipping(orderId).orElse(null), issues.forOrder(orderId));
  }

  private static void requireStaff() {
    PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .filter(p -> StaffOrderRules.allowed(p.roles()))
        .orElseThrow(
            () ->
                new PmartException(
                    ErrorCode.FORBIDDEN, "주문 조회 권한이 없습니다(OPERATOR_ADMIN·CS_AGENT·RIDER_OPS)."));
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
