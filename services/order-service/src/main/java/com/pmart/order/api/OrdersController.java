package com.pmart.order.api;

import com.pmart.common.web.CommandResponse;
import com.pmart.order.api.generated.OrdersApi;
import com.pmart.order.api.generated.model.ArrivalWindow;
import com.pmart.order.api.generated.model.GetWithdrawalEligibility200ResponseAsOf;
import com.pmart.order.api.generated.model.ListOrders200Response;
import com.pmart.order.api.generated.model.ListOrders200ResponsePage;
import com.pmart.order.api.generated.model.Order;
import com.pmart.order.api.generated.model.OrderLine;
import com.pmart.order.api.generated.model.OrderPayment;
import com.pmart.order.api.generated.model.OrderRepresentativeItem;
import com.pmart.order.api.generated.model.OrderSourcesInner;
import com.pmart.order.api.generated.model.OrderStatus;
import com.pmart.order.api.generated.model.OrderSummary;
import com.pmart.order.api.generated.model.OrderSummaryLine;
import com.pmart.order.api.generated.model.Quote;
import com.pmart.order.api.generated.model.StartCheckout202Response;
import com.pmart.order.application.DraftService;
import com.pmart.order.application.OrderService;
import com.pmart.order.infrastructure.OrderStore;
import com.pmart.order.infrastructure.OrderStore.LineRow;
import com.pmart.order.infrastructure.OrderStore.OrderRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 OrdersApi — 확정 주문 상세·목록(고객 본인), 예약 시간 변경 요청·결과(S4, 0.10.0). 업무 규칙은 application/domain 에 두고
 * 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class OrdersController implements OrdersApi {

  private final OrderService orders;
  private final com.pmart.order.application.TimeChangeService timeChanges;
  private final Clock clock;
  private final com.pmart.order.application.DeliveryLinks deliveries;
  private final com.pmart.order.application.CenterNames centerNames;
  private final com.pmart.order.application.PurchaseHistoryService purchases;

  public OrdersController(
      OrderService orders,
      com.pmart.order.application.TimeChangeService timeChanges,
      Clock clock,
      com.pmart.order.application.DeliveryLinks deliveries,
      com.pmart.order.application.CenterNames centerNames,
      com.pmart.order.application.PurchaseHistoryService purchases) {
    this.purchases = purchases;
    this.orders = orders;
    this.timeChanges = timeChanges;
    this.clock = clock;
    this.deliveries = deliveries;
    this.centerNames = centerNames;
  }

  /** 0.24.0 본인 자주 산 상품(①·②) — 규칙·권한은 {@code PurchaseHistoryService}. */
  @Override
  public ResponseEntity<com.pmart.order.api.generated.model.FrequentItems> listFrequentItems(
      Integer windowDays, Integer minOrders, Integer limit) {
    var f = purchases.frequentItems(windowDays, minOrders, limit);
    return ResponseEntity.ok(
        new com.pmart.order.api.generated.model.FrequentItems()
            .from(time(f.from()))
            .items(
                f.rows().stream()
                    .map(
                        r ->
                            new com.pmart.order.api.generated.model.FrequentItemsItemsInner()
                                .sku(r.sku())
                                .productId(r.productId())
                                .productName(r.productName())
                                .optionLabel(r.optionLabel())
                                .orderCount(r.orderCount())
                                .totalQuantity(r.totalQuantity())
                                .lastPurchasedAt(time(r.lastPurchasedAt()))
                                .imageUrl(
                                    r.productId() == null ? null : f.images().get(r.productId())))
                    .toList()));
  }

  /** S4 예약 시간 변경 요청(202 — 접수, 결과는 조회). */
  @Override
  public ResponseEntity<com.pmart.order.api.generated.model.StartCheckout202Response>
      requestReservationTimeChange(
          String orderId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.order.api.generated.model.RequestReservationTimeChangeRequest req,
          @org.jspecify.annotations.Nullable Integer xExpectedVersion) {
    var w = req.getNewArrivalWindow();
    String requestId =
        timeChanges.request(
            DraftService.currentCustomer(),
            orderId,
            idempotencyKey,
            xSourceSystem,
            xExpectedVersion,
            w.getDate(),
            w.getStart(),
            w.getEnd());
    return ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED)
        .body(
            new com.pmart.order.api.generated.model.StartCheckout202Response()
                .status(
                    com.pmart.order.api.generated.model.StartCheckout202Response.StatusEnum
                        .ACCEPTED)
                .requestId(idempotencyKey)
                .resultId(requestId)
                .nextAction("QUERY_RESULT")
                .occurredAt(java.time.OffsetDateTime.now(clock)));
  }

  @Override
  public ResponseEntity<com.pmart.order.api.generated.model.GetReservationTimeChange200Response>
      getReservationTimeChange(String orderId, String requestId) {
    var r = timeChanges.get(DraftService.currentCustomer(), orderId, requestId);
    return ResponseEntity.ok(
        new com.pmart.order.api.generated.model.GetReservationTimeChange200Response()
            .requestId(r.requestId())
            .state(
                com.pmart.order.api.generated.model.GetReservationTimeChange200Response.StateEnum
                    .fromValue(r.state()))
            .reason(r.reason())
            .previousArrivalWindow(
                new ArrivalWindow().date(r.fromDate()).start(r.fromStart()).end(r.fromEnd()))
            .newArrivalWindow(
                new ArrivalWindow().date(r.toDate()).start(r.toStart()).end(r.toEnd()))
            .requestedAt(r.requestedAt().atOffset(java.time.ZoneOffset.UTC))
            .decidedAt(
                r.decidedAt() == null ? null : r.decidedAt().atOffset(java.time.ZoneOffset.UTC)));
  }

  @Override
  public ResponseEntity<Order> getOrder(String orderId) {
    OrderService.OrderView v = orders.get(DraftService.currentCustomer(), orderId);
    OrderRow o = v.order();
    return ResponseEntity.ok(
        new Order()
            .orderId(o.orderId())
            .orderNumber(o.orderNumber())
            .version(o.version())
            .status(OrderStatus.fromValue(o.status()))
            .mfcId(o.mfcId())
            .mfcName(centerNames.name(o.mfcId())) // BR-62(0.15.0) — 고객에게 보이는 센터 이름
            .deliveryMode(o.deliveryMode())
            .arrivalWindow(
                o.arrivalDate() == null
                    ? null
                    : new ArrivalWindow()
                        .date(o.arrivalDate())
                        .start(o.arrivalStart())
                        .end(o.arrivalEnd()))
            .confirmedAt(time(o.confirmedAt()))
            .outOfStockPreference(
                Order.OutOfStockPreferenceEnum.fromValue(o.outOfStockPreference()))
            .shoppingAddressId(o.shoppingAddressId())
            .shippingAddress(ShippingMapping.of(v.shipping()))
            .deliveryNote(o.deliveryNote())
            .quote(
                new Quote()
                    .quoteVersion(o.quoteVersion())
                    .priceVersion(o.priceVersion())
                    .itemAmount((int) o.itemAmount())
                    .deliveryTipBase((int) o.deliveryTipBase())
                    .deliveryTip((int) o.deliveryTip())
                    .deliveryTipReason(Quote.DeliveryTipReasonEnum.fromValue(o.deliveryTipReason()))
                    .bagFee((int) o.bagFee())
                    .totalAmount((int) o.totalAmount())
                    // ADR-0082: 쿠폰 할인 = 상품액 + 배달팁 + 봉투값 − 총액, 기간 할인 = 줄 할인 합 − 쿠폰
                    .couponDiscount(couponDiscount(o))
                    .promotionDiscount(
                        (int)
                            Math.max(
                                0,
                                v.lines().stream().mapToLong(l -> l.discountShare()).sum()
                                    - couponDiscount(o)))
                    .firstPurchaseBenefit(
                        o.firstPurchaseApplied()
                            ? Quote.FirstPurchaseBenefitEnum.CONFIRMED
                            : Quote.FirstPurchaseBenefitEnum.NOT_ELIGIBLE)
                    .weightG(o.weightG())
                    .plannedShipments(o.plannedShipments())
                    // 0.19.0 — 주문서 견적의 쿠폰(이름 포함), 안 썼으면 null
                    .coupon(
                        v.coupon() == null
                            ? null
                            : new com.pmart.order.api.generated.model.QuoteCoupon()
                                .couponId(v.coupon().couponId())
                                .applicable(true)
                                .name(v.coupon().name())))
            .lines(
                v.lines().stream()
                    .map(
                        l ->
                            line(l)
                                .imageUrl(
                                    l.productId() == null ? null : v.images().get(l.productId())))
                    .toList())
            .shipments(shipments(o.orderId(), v.lines()))
            .payment(new OrderPayment().paymentAttemptId(o.paymentAttemptId()).state("APPROVED"))
            .claims(List.of())
            .sources(
                List.of(
                    new OrderSourcesInner()
                        .system("order-service")
                        .asOf(
                            new GetWithdrawalEligibility200ResponseAsOf()
                                .asOf(time(clock.instant()))
                                .sourceVersion(o.version())
                                .freshness(
                                    GetWithdrawalEligibility200ResponseAsOf.FreshnessEnum
                                        .CURRENT)))));
  }

  @Override
  public ResponseEntity<ListOrders200Response> listOrders(
      Integer size,
      @Nullable String cursor,
      @Nullable OrderStatus status,
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable String q) {
    OrderService.Page page =
        orders.list(
            DraftService.currentCustomer(),
            size,
            cursor,
            status == null ? null : status.getValue(),
            from,
            to,
            q);
    return ResponseEntity.ok(
        new ListOrders200Response()
            .items(
                page.items().stream()
                    .map(
                        s ->
                            summary(
                                s,
                                page.lines().getOrDefault(s.orderId(), List.of()),
                                page.images()))
                    .toList())
            .page(new ListOrders200ResponsePage().nextCursor(page.nextCursor()).size(page.size())));
  }

  /** 주문 내역에서 숨기기(0.12.0) — 200 APPLIED, resultId = orderId. */
  @Override
  public ResponseEntity<StartCheckout202Response> hideOrder(
      String orderId, String idempotencyKey, String xSourceSystem) {
    CommandResponse r =
        orders.hide(DraftService.currentCustomer(), orderId, idempotencyKey, xSourceSystem);
    return ResponseEntity.ok(
        new StartCheckout202Response()
            .status(StartCheckout202Response.StatusEnum.fromValue(r.status().name()))
            .requestId(r.requestId())
            .resultId(r.resultId())
            .version(r.version())
            .retryable(r.retryable())
            .nextAction(r.nextAction())
            .occurredAt(time(r.occurredAt())));
  }

  /** 목록 한 줄. lines·mfcName 은 BR-68(0.17.0) — 센터 이름은 상세와 같은 {@code CenterNames}(10분 사본). */
  private OrderSummary summary(
      OrderStore.Summary s,
      List<OrderStore.PreviewLine> lines,
      java.util.Map<String, String> images) {
    return new OrderSummary()
        .orderId(s.orderId())
        .orderNumber(s.orderNumber())
        .status(OrderStatus.fromValue(s.status()))
        .orderedAt(time(s.confirmedAt()))
        .totalAmount((int) s.totalAmount())
        .itemCount(s.itemCount())
        .deliveryMode(OrderSummary.DeliveryModeEnum.fromValue(s.deliveryMode()))
        .representativeItem(
            s.firstSku() == null
                ? null
                : new OrderRepresentativeItem()
                    .productId(s.firstProductId())
                    .sku(s.firstSku())
                    .productName(s.firstProductName())
                    .optionLabel(s.firstOptionLabel())
                    .quantity(s.firstQuantity()))
        .mfcName(centerNames.name(s.mfcId()))
        .lines(
            lines.stream()
                .map(
                    l ->
                        new OrderSummaryLine()
                            .orderLineId(l.orderLineId())
                            .productId(l.productId())
                            .sku(l.sku())
                            .productName(l.productName())
                            .optionLabel(l.optionLabel())
                            // BR-71(0.19.0) — 지금 대표 사진
                            .imageUrl(l.productId() == null ? null : images.get(l.productId()))
                            .quantity(l.quantity())
                            .lineAmount((int) l.lineAmount())
                            // 0.26.0 ADR-0090 수량 행사
                            .paidQuantity(l.quantity() - l.freeQuantity())
                            .freeQuantity(l.freeQuantity())
                            .offerPromotionId(l.offerPromotionId()))
                .toList());
  }

  /** 주문행 → 계약 모델(고객 상세·직원 상세 공용). */
  static OrderLine line(LineRow l) {
    return new OrderLine()
        .orderLineId(l.orderLineId())
        .sku(l.sku())
        .productId(l.productId())
        .productName(l.productName())
        .optionLabel(l.optionLabel())
        .cartItemId(l.cartItemId())
        .unitsPerSku(l.unitsPerSku())
        .unitPrice((int) l.unitPrice())
        .lineAmount((int) l.lineAmount())
        .discountShare((int) l.discountShare())
        .originalQty(l.originalQty())
        .unfulfilledQty(l.unfulfilledQty())
        .handedOverQty(l.handedOverQty())
        .cancelledBeforeShipQty(l.cancelledBeforeShipQty())
        .deliveredQty(l.deliveredQty())
        .returnedQty(l.returnedQty())
        // 0.26.0 ADR-0090 수량 행사
        .paidQuantity(l.quantity() - l.freeQuantity())
        .freeQuantity(l.freeQuantity())
        .offerPromotionId(l.offerPromotionId());
  }

  private static OffsetDateTime time(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  /** 배송 짝(BR-61, 0.15.0) — 첫 출고(<orderId>-S1)는 주문 줄 전체, 재발송 출고는 줄 없이. */
  private List<com.pmart.order.api.generated.model.Shipment> shipments(
      String orderId, List<LineRow> lines) {
    return deliveries.byOrder(orderId).stream()
        .map(
            d ->
                new com.pmart.order.api.generated.model.Shipment()
                    .shipmentId(d.shipmentId())
                    .deliveryId(d.deliveryId())
                    .state(
                        com.pmart.order.api.generated.model.Shipment.StateEnum.fromValue(d.state()))
                    .lines(
                        d.shipmentId().equals(orderId + "-S1")
                            ? lines.stream()
                                .map(
                                    l ->
                                        new com.pmart.order.api.generated.model.ShipmentLinesInner()
                                            .orderLineId(l.orderLineId())
                                            .qty(l.originalQty()))
                                .toList()
                            : List.of()))
        .toList();
  }

  private static int couponDiscount(com.pmart.order.infrastructure.OrderStore.OrderRow o) {
    return (int) Math.max(0, o.itemAmount() + o.deliveryTip() + o.bagFee() - o.totalAmount());
  }
}
