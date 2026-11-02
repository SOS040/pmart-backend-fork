package com.pmart.order.api;

import com.pmart.order.api.generated.OrdersApi;
import com.pmart.order.api.generated.model.ArrivalWindow;
import com.pmart.order.api.generated.model.GetWithdrawalEligibility200ResponseAsOf;
import com.pmart.order.api.generated.model.ListOrders200Response;
import com.pmart.order.api.generated.model.ListOrders200ResponsePage;
import com.pmart.order.api.generated.model.Order;
import com.pmart.order.api.generated.model.OrderLine;
import com.pmart.order.api.generated.model.OrderPayment;
import com.pmart.order.api.generated.model.OrderSourcesInner;
import com.pmart.order.api.generated.model.OrderStatus;
import com.pmart.order.api.generated.model.OrderSummary;
import com.pmart.order.api.generated.model.Quote;
import com.pmart.order.application.DraftService;
import com.pmart.order.application.OrderService;
import com.pmart.order.infrastructure.OrderStore.OrderRow;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 OrdersApi — 확정 주문 상세·목록(고객 본인). 예약 시간 변경(S4)은 인터페이스 default 가 501 을 돌려준다. 업무 규칙은
 * application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class OrdersController implements OrdersApi {

  private final OrderService orders;
  private final Clock clock;

  public OrdersController(OrderService orders, Clock clock) {
    this.orders = orders;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<Order> getOrder(String orderId) {
    OrderService.OrderView v = orders.get(DraftService.currentCustomer(), orderId);
    OrderRow o = v.order();
    return ResponseEntity.ok(
        new Order()
            .orderId(o.orderId())
            .version(o.version())
            .status(OrderStatus.fromValue(o.status()))
            .mfcId(o.mfcId())
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
                    .firstPurchaseBenefit(
                        o.firstPurchaseApplied()
                            ? Quote.FirstPurchaseBenefitEnum.CONFIRMED
                            : Quote.FirstPurchaseBenefitEnum.NOT_ELIGIBLE)
                    .weightG(o.weightG())
                    .plannedShipments(o.plannedShipments()))
            .lines(
                v.lines().stream()
                    .map(
                        l ->
                            new OrderLine()
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
                                .returnedQty(l.returnedQty()))
                    .toList())
            .shipments(List.of())
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
      Integer size, @Nullable String cursor, @Nullable OrderStatus status) {
    OrderService.Page page =
        orders.list(
            DraftService.currentCustomer(),
            size,
            cursor,
            status == null ? null : status.getValue());
    return ResponseEntity.ok(
        new ListOrders200Response()
            .items(
                page.items().stream()
                    .map(
                        s ->
                            new OrderSummary()
                                .orderId(s.orderId())
                                .status(OrderStatus.fromValue(s.status()))
                                .orderedAt(time(s.confirmedAt()))
                                .totalAmount((int) s.totalAmount())
                                .itemCount(s.itemCount()))
                    .toList())
            .page(new ListOrders200ResponsePage().nextCursor(page.nextCursor()).size(page.size())));
  }

  private static OffsetDateTime time(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
