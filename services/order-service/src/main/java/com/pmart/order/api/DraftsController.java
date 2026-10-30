package com.pmart.order.api;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.order.api.generated.DraftsApi;
import com.pmart.order.api.generated.model.ArrivalWindow;
import com.pmart.order.api.generated.model.CreateDraftRequest;
import com.pmart.order.api.generated.model.DeliveryOption;
import com.pmart.order.api.generated.model.DeliveryOptionDeliveryTip;
import com.pmart.order.api.generated.model.DeliveryOptionEta;
import com.pmart.order.api.generated.model.DeliveryOptions;
import com.pmart.order.api.generated.model.DraftBlocker;
import com.pmart.order.api.generated.model.DraftDelivery;
import com.pmart.order.api.generated.model.DraftLine;
import com.pmart.order.api.generated.model.OrderDraft;
import com.pmart.order.api.generated.model.OrderStatus;
import com.pmart.order.api.generated.model.Quote;
import com.pmart.order.api.generated.model.ResourceState;
import com.pmart.order.application.DeliveryEvaluator;
import com.pmart.order.application.DraftService;
import com.pmart.order.domain.OrderRules;
import com.pmart.order.infrastructure.Upstreams;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 DraftsApi — 주문 초안 생성·조회, 배송 방식 안내(BR-13). 결제 진입(checkouts)은 다음 단계(501). 변환만 한다. */
@RestController
public class DraftsController implements DraftsApi {

  private final DraftService drafts;
  private final DeliveryEvaluator delivery;
  private final Upstreams upstreams;
  private final Clock clock;

  public DraftsController(
      DraftService drafts, DeliveryEvaluator delivery, Upstreams upstreams, Clock clock) {
    this.drafts = drafts;
    this.delivery = delivery;
    this.upstreams = upstreams;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<OrderDraft> createOrderDraft(
      String idempotencyKey,
      String xSourceSystem,
      CreateDraftRequest body,
      @Nullable String traceparent) {
    String accountId = DraftService.currentCustomer();
    DraftService.Request req =
        new DraftService.Request(
            body.getPurchaseIntentId(),
            body.getItems().stream()
                .map(i -> new DraftService.Item(i.getCartItemId(), i.getSku(), i.getQuantity()))
                .toList(),
            body.getShoppingAddressId(),
            body.getExpectedMfcId(),
            body.getDeliveryMode().getValue(),
            body.getArrivalWindow() == null
                ? null
                : new DeliveryEvaluator.Window(
                    body.getArrivalWindow().getDate(),
                    body.getArrivalWindow().getStart(),
                    body.getArrivalWindow().getEnd()),
            body.getOutOfStockPreference().getValue(),
            body.getDeliveryNote());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(draft(drafts.create(accountId, idempotencyKey, xSourceSystem, req)));
  }

  @Override
  public ResponseEntity<OrderDraft> getOrderDraft(String draftId) {
    return ResponseEntity.ok(draft(drafts.get(DraftService.currentCustomer(), draftId)));
  }

  /** 회원·비회원. 첫 구매 무료는 회원만 판정한다(비회원은 30,000원 기준만). */
  @Override
  public ResponseEntity<DeliveryOptions> getDeliveryOptions(
      String mfcId, @Nullable Integer itemAmount) {
    Instant now = clock.instant();
    String member =
        PmartPrincipal.current()
            .filter(p -> p.type() == PmartPrincipal.PrincipalType.CUSTOMER)
            .map(PmartPrincipal::subject)
            .orElse(null);
    boolean firstPurchase = false;
    if (member != null && itemAmount != null && itemAmount < OrderRules.FREE_DELIVERY_AMOUNT) {
      try {
        firstPurchase = upstreams.firstPurchaseCandidate(member);
      } catch (RuntimeException e) {
        firstPurchase = false; // 안내용 — 판정 못 하면 무료로 표시하지 않는다
      }
    }
    final boolean expected = firstPurchase;
    List<DeliveryOption> options =
        OrderRules.MODES.stream()
            .map(
                mode -> {
                  DeliveryEvaluator.ModeResult m = delivery.evaluate(mode, mfcId, now);
                  OrderRules.Tip tip =
                      OrderRules.tip(mode, itemAmount == null ? 0 : itemAmount, expected);
                  return new DeliveryOption()
                      .mode(DeliveryOption.ModeEnum.fromValue(mode))
                      .availability(DeliveryOption.AvailabilityEnum.fromValue(m.availability()))
                      .unavailableReason(
                          m.reason() == null
                              ? null
                              : DeliveryOption.UnavailableReasonEnum.fromValue(m.reason()))
                      // 예상 소요시간 정책값 미설정(사용자 결정 2026-10-31) — 0·추정값으로 채우지 않는다
                      .eta(new DeliveryOptionEta().state(DeliveryOptionEta.StateEnum.NOT_SET))
                      .deliveryTip(
                          new DeliveryOptionDeliveryTip()
                              .base((int) OrderRules.tipBase(mode))
                              .applied(itemAmount == null ? null : (int) tip.applied())
                              .reason(
                                  itemAmount == null
                                      ? null
                                      : DeliveryOptionDeliveryTip.ReasonEnum.fromValue(
                                          tip.reason())))
                      .earliestSlot(window(m.earliestSlot()));
                })
            .toList();
    return ResponseEntity.ok(
        new DeliveryOptions().mfcId(mfcId).evaluatedAt(time(now)).options(options));
  }

  private static OrderDraft draft(DraftService.View v) {
    DraftService.Snapshot s = v.snapshot();
    DraftService.Quote q = s.quote();
    return new OrderDraft()
        .draftId(v.row().draftId())
        .purchaseIntentId(v.row().purchaseIntentId())
        .version(v.row().version())
        .status(v.expired() ? OrderStatus.EXPIRED : OrderStatus.DRAFT)
        .mfcId(v.row().mfcId())
        .quote(
            new Quote()
                .quoteVersion(q.quoteVersion())
                .quoteExpiresAt(time(v.row().quoteExpiresAt()))
                .priceVersion(q.priceVersion())
                .itemAmount((int) q.itemAmount())
                .deliveryTipBase((int) q.deliveryTipBase())
                .deliveryTip((int) q.deliveryTip())
                .deliveryTipReason(Quote.DeliveryTipReasonEnum.fromValue(q.deliveryTipReason()))
                .bagFee((int) q.bagFee())
                .totalAmount((int) q.totalAmount())
                .minOrderSatisfied(q.minOrderSatisfied())
                .minOrderAmount((int) q.minOrderAmount())
                .minOrderShortfall((int) q.minOrderShortfall())
                .firstPurchaseBenefit(
                    Quote.FirstPurchaseBenefitEnum.fromValue(q.firstPurchaseBenefit()))
                .weightG(q.weightG())
                .plannedShipments(q.plannedShipments()))
        .resources(
            new ResourceState()
                .inventoryReservation(ResourceState.InventoryReservationEnum.NOT_REQUESTED)
                .capacityHold(ResourceState.CapacityHoldEnum.NOT_REQUESTED)
                .slotHold(
                    OrderRules.SCHEDULED.equals(s.delivery().deliveryMode())
                        ? ResourceState.SlotHoldEnum.UNKNOWN
                        : ResourceState.SlotHoldEnum.NOT_REQUIRED)
                .payment(ResourceState.PaymentEnum.NOT_STARTED))
        // 결제 진입(checkouts)은 다음 단계 — 지금은 검토까지. 만료면 새 견적
        .nextAction(
            v.expired() ? OrderDraft.NextActionEnum.RE_QUOTE : OrderDraft.NextActionEnum.REVIEW)
        .lines(
            s.lines().stream()
                .map(
                    l ->
                        new DraftLine()
                            .cartItemId(l.cartItemId())
                            .sku(l.sku())
                            .productId(l.productId())
                            .quantity(l.quantity())
                            .unitPrice((int) l.unitPrice())
                            .lineAmount((int) l.lineAmount())
                            .weightG(l.weightG()))
                .toList())
        .delivery(
            new DraftDelivery()
                .deliveryMode(DraftDelivery.DeliveryModeEnum.fromValue(s.delivery().deliveryMode()))
                .arrivalWindow(window(s.delivery().arrivalWindow()))
                .shoppingAddressId(s.delivery().shoppingAddressId())
                .deliveryNote(s.delivery().deliveryNote())
                .outOfStockPreference(
                    DraftDelivery.OutOfStockPreferenceEnum.fromValue(
                        s.delivery().outOfStockPreference())))
        .blockers(
            s.blockers().stream()
                .map(
                    b ->
                        new DraftBlocker()
                            .code(DraftBlocker.CodeEnum.fromValue(b.code()))
                            .detail(b.detail()))
                .toList())
        .createdAt(time(v.row().createdAt()));
  }

  private static @Nullable ArrivalWindow window(DeliveryEvaluator.@Nullable Window w) {
    return w == null ? null : new ArrivalWindow().date(w.date()).start(w.start()).end(w.end());
  }

  private static OffsetDateTime time(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
