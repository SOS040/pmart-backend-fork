package com.pmart.order.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.order.domain.OrderRules;
import com.pmart.order.domain.ShippingAddress;
import com.pmart.order.infrastructure.DraftStore;
import com.pmart.order.infrastructure.DraftStore.DraftRow;
import com.pmart.order.infrastructure.Upstreams;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 주문 초안(Commerce 8·10장, OMS 초안). 정본을 다시 확인해 금액을 10분 고정한다. 결제 진입·자원 확보·첫 구매 자격 점유는 이후 단계.
 *
 * <ul>
 *   <li>거절(422): 구매 불가 계정, 배송지 권역 밖·판정 불명, 판매 불가 SKU, 구매 상한, 가격 미설정, 나눌 수 없는 중량.
 *   <li>차단 사유(초안은 만든다, Commerce 10.7 "모든 이유를 다시 검사"): 최소 주문 미달(부족액), 배송 방식 불가, 예약 시간 미선택·불가.
 *   <li>같은 구매 의도(purchaseIntentId)의 유효한 초안이 있으면 그것을 돌려준다. 10분이 지나면 EXPIRED → 새 견적(RE_QUOTE).
 *   <li>첫 구매 무료는 예상(EXPECTED)까지만 — 실제 자격 확보는 결제 진입 때.
 * </ul>
 */
@Service
public class DraftService {

  private static final Logger log = LoggerFactory.getLogger(DraftService.class);

  public record Item(String cartItemId, String sku, int quantity) {}

  public record Request(
      String purchaseIntentId,
      List<Item> items,
      String shoppingAddressId,
      @Nullable String expectedMfcId,
      String deliveryMode,
      DeliveryEvaluator.@Nullable Window arrivalWindow,
      String outOfStockPreference,
      @Nullable String deliveryNote) {}

  public record Line(
      String cartItemId,
      String sku,
      String productId,
      int quantity,
      long unitPrice,
      long lineAmount,
      int weightG,
      // 2026-10-31 결제 확정 단계에서 추가(OrderConfirmed 필수 값). 그 전 초안은 null — 확정 때 product 로 채운다
      @Nullable Integer unitsPerSku) {}

  /**
   * @param shippingAddress 주문 당시 배송지·수령 연락처 사본(BR-37). 2026-11-02 이전 초안은 null(JSON 에 없음)
   */
  public record Delivery(
      String deliveryMode,
      DeliveryEvaluator.@Nullable Window arrivalWindow,
      String shoppingAddressId,
      @Nullable String deliveryNote,
      String outOfStockPreference,
      @Nullable ShippingAddress shippingAddress) {}

  public record Quote(
      int quoteVersion,
      int priceVersion,
      long itemAmount,
      long deliveryTipBase,
      long deliveryTip,
      String deliveryTipReason,
      long bagFee,
      long totalAmount,
      boolean minOrderSatisfied,
      long minOrderAmount,
      long minOrderShortfall,
      String firstPurchaseBenefit,
      int weightG,
      int plannedShipments) {}

  public record Snapshot(
      List<Line> lines, Delivery delivery, Quote quote, List<OrderRules.Blocker> blockers) {}

  public record View(DraftRow row, Snapshot snapshot, boolean expired) {}

  private final DraftStore store;
  private final Upstreams upstreams;
  private final DeliveryEvaluator delivery;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;

  public DraftService(
      DraftStore store,
      Upstreams upstreams,
      DeliveryEvaluator delivery,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.upstreams = upstreams;
    this.delivery = delivery;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
  }

  public static String currentCustomer() {
    return PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.CUSTOMER)
        .map(PmartPrincipal::subject)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "로그인한 고객만 주문할 수 있습니다."));
  }

  public View create(String accountId, String requestId, String sourceSystem, Request req) {
    Instant now = clock.instant();
    // 같은 구매 의도의 유효 초안 → 그대로(새 견적·새 초안을 만들지 않는다)
    var existing = store.latestByIntent(accountId, req.purchaseIntentId());
    if (existing.isPresent() && now.isBefore(existing.get().quoteExpiresAt())) {
      return view(existing.get(), now);
    }
    if (!upstreams.purchasable(accountId)) {
      throw reject("ACCOUNT_NOT_PURCHASABLE", "지금 주문할 수 없는 계정 상태입니다.");
    }
    Upstreams.Address address = upstreams.shoppingAddress(accountId, req.shoppingAddressId(), now);
    if (!"SERVICEABLE".equals(address.serviceability()) || address.mfcId() == null) {
      throw "OUT_OF_SERVICE_AREA".equals(address.serviceability())
          ? reject("OUT_OF_SERVICE_AREA", "배송할 수 없는 지역입니다.")
          : reject("SERVICEABILITY_UNKNOWN", "배송 가능 여부를 확인하지 못했습니다. 주소를 다시 선택해 주세요.");
    }
    String mfcId = address.mfcId();
    if (req.expectedMfcId() != null && !req.expectedMfcId().equals(mfcId)) {
      throw new PmartException(
          "MFC_CHANGED", HttpStatus.CONFLICT, "담당 센터가 바뀌었습니다. 장바구니를 다시 확인해 주세요.", false, List.of());
    }
    Map<String, Integer> qty = new LinkedHashMap<>();
    for (Item i : req.items()) {
      if (qty.put(i.sku(), i.quantity()) != null) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "같은 SKU 가 두 번 있습니다: " + i.sku());
      }
    }
    Map<String, Upstreams.Sku> skus = upstreams.skus(List.copyOf(qty.keySet()));
    List<PmartException.Detail> notSaleable = new ArrayList<>();
    for (String sku : qty.keySet()) {
      Upstreams.Sku s = skus.get(sku);
      if (s == null || !"ON_SALE".equals(s.saleState())) {
        notSaleable.add(new PmartException.Detail(sku, s == null ? "NOT_FOUND" : s.saleState()));
      }
    }
    if (!notSaleable.isEmpty()) {
      throw new PmartException(
          "SKU_NOT_SALEABLE",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "지금 살 수 없는 상품이 있습니다.",
          false,
          notSaleable);
    }
    JsonNode violation = upstreams.purchaseLimitViolation(accountId, qty);
    if (violation != null) {
      throw new PmartException(
          "QUANTITY_LIMIT_EXCEEDED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "같은 상품은 최대 " + violation.path("limitUnits").asInt() + "개까지 살 수 있습니다.",
          false,
          List.of(new PmartException.Detail(violation.path("baseProductId").asString(), "LIMIT")));
    }
    List<int[]> weights = new ArrayList<>();
    int totalWeight = 0;
    for (Map.Entry<String, Integer> e : qty.entrySet()) {
      int w = skus.get(e.getKey()).weightG();
      weights.add(new int[] {w, e.getValue()});
      totalWeight += w * e.getValue();
    }
    int shipments = OrderRules.plannedShipments(weights);
    if (shipments < 0) {
      throw reject("WEIGHT_UNSPLITTABLE", "한 개가 10kg 을 넘는 상품은 배송할 수 없습니다.");
    }
    Upstreams.Priced priced = upstreams.quote(accountId, mfcId, qty);
    String firstPurchase =
        upstreams.firstPurchaseCandidate(accountId) ? "EXPECTED" : "NOT_ELIGIBLE";
    OrderRules.Amounts amounts =
        OrderRules.amounts(
            req.deliveryMode(), priced.itemAmount(), "EXPECTED".equals(firstPurchase));

    List<OrderRules.Blocker> blockers = new ArrayList<>();
    if (!amounts.minOrderSatisfied()) {
      blockers.add(
          new OrderRules.Blocker("MIN_ORDER_AMOUNT", amounts.minOrderShortfall() + "원 더 담아 주세요"));
    }
    if (OrderRules.SCHEDULED.equals(req.deliveryMode())) {
      if (req.arrivalWindow() == null) {
        blockers.add(new OrderRules.Blocker("SLOT_REQUIRED", "예약 시간을 골라 주세요"));
      } else {
        delivery
            .slotProblem(mfcId, req.arrivalWindow())
            .ifPresent(p -> blockers.add(new OrderRules.Blocker("SLOT_UNAVAILABLE", p)));
      }
    } else {
      DeliveryEvaluator.ModeResult m = delivery.evaluate(req.deliveryMode(), mfcId, now);
      if (!m.available()) {
        blockers.add(new OrderRules.Blocker("DELIVERY_MODE_UNAVAILABLE", m.reason()));
      }
    }

    List<Line> lines = new ArrayList<>();
    for (Item i : req.items()) {
      Upstreams.PricedLine p = priced.lines().get(i.sku());
      Upstreams.Sku s = skus.get(i.sku());
      lines.add(
          new Line(
              i.cartItemId(),
              i.sku(),
              s.productId(),
              i.quantity(),
              p == null ? 0 : p.unitPrice(),
              p == null ? 0 : p.lineAmount(),
              s.weightG() * i.quantity(),
              s.unitsPerSku()));
    }
    Snapshot snapshot =
        new Snapshot(
            lines,
            new Delivery(
                req.deliveryMode(),
                req.arrivalWindow(),
                req.shoppingAddressId(),
                req.deliveryNote(),
                req.outOfStockPreference(),
                address.shipping()),
            new Quote(
                1,
                priced.priceVersion(),
                amounts.itemAmount(),
                amounts.tip().base(),
                amounts.tip().applied(),
                amounts.tip().reason(),
                amounts.bagFee(),
                amounts.total(),
                amounts.minOrderSatisfied(),
                OrderRules.MIN_ORDER_AMOUNT,
                amounts.minOrderShortfall(),
                firstPurchase,
                totalWeight,
                shipments),
            OrderRules.ordered(blockers));
    String draftId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createOrderDraft", accountId),
                req,
                String.class,
                () -> {
                  String id = "DRF-" + UUID.randomUUID();
                  store.insert(
                      new DraftRow(
                          id,
                          accountId,
                          req.purchaseIntentId(),
                          "DRAFT",
                          mfcId,
                          mapper.writeValueAsString(snapshot),
                          1,
                          now,
                          OrderRules.quoteExpiresAt(now),
                          null,
                          null));
                  return id;
                })
            .value();
    log.info("주문 초안 {} 상품액 {} 차단 {}", draftId, amounts.itemAmount(), blockers.size());
    return get(accountId, draftId);
  }

  /** 내 초안. 남의 초안은 404(존재를 드러내지 않음). */
  public View get(String accountId, String draftId) {
    DraftRow row =
        store
            .draft(draftId)
            .filter(d -> d.accountId().equals(accountId))
            .orElseThrow(() -> PmartException.notFound("DRAFT_NOT_FOUND", "주문서가 없습니다."));
    return view(row, clock.instant());
  }

  private View view(DraftRow row, Instant now) {
    return new View(
        row, mapper.readValue(row.snapshot(), Snapshot.class), !now.isBefore(row.quoteExpiresAt()));
  }

  private static PmartException reject(String code, String message) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }
}
