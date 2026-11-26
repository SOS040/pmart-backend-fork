package com.pmart.pricing.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.pricing.domain.PriceRules;
import com.pmart.pricing.domain.PriceVersion;
import com.pmart.pricing.infrastructure.PriceStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 견적 계산 — 기본가 → 기간 할인 → 장바구니 쿠폰(ADR-0082, 0.2.0). 결과는 quoteId 로 보존해 응답 유실 시 재조회한다. 가격 미설정 SKU 가
 * 하나라도 있으면 전체 PRICE_NOT_SET. 배달팁·봉투값·최소주문은 order 책임이라 계산하지 않는다.
 */
@Service
public class QuoteService {

  public record QuoteItem(String lineRef, String sku, int quantity) {}

  public record QuoteLine(
      String lineRef,
      String sku,
      int quantity,
      long unitPrice,
      int priceVersion,
      long lineAmount,
      long discountShare,
      long netAmount,
      // ADR-0082(0.2.0): 기간 할인(개당 × 수량)·쿠폰 안분·걸린 행사. 그 전 견적 JSON 은 0·null
      long promotionDiscount,
      long couponShare,
      @Nullable String promotionId,
      // ADR-0090(0.4.0): 수량 행사 — quantity = 총 수량(유상 + 무료), 무료분이 promotionDiscount. 그 전 견적 JSON 은
      // 0·null
      int paidQuantity,
      int freeQuantity,
      @Nullable String offerPromotionId,
      @Nullable String offerName,
      int offerBuyQuantity,
      int offerFreeQuantity) {}

  /** 요청 쿠폰 결과(0.2.0). */
  public record CouponResult(
      String couponId,
      boolean applicable,
      @Nullable String reason,
      long discountAmount,
      @Nullable String promotionId,
      @Nullable String name) {}

  public record Quote(
      String quoteId,
      String requestId,
      int priceVersion,
      List<QuoteLine> lines,
      long itemAmount,
      long discountTotal,
      List<String> appliedStages,
      Instant priceAt,
      Instant calculatedAt,
      long baseItemAmount,
      long promotionDiscount,
      long couponDiscount,
      @Nullable CouponResult coupon) {}

  private final PriceStore store;
  private final com.pmart.pricing.infrastructure.PromotionClient promotion;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;

  public QuoteService(
      PriceStore store,
      com.pmart.pricing.infrastructure.PromotionClient promotion,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.promotion = promotion;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Quote create(
      String requestId,
      String sourceSystem,
      @Nullable Instant at,
      @Nullable String accountId,
      @Nullable String couponId,
      List<QuoteItem> items,
      Object body) {
    String actor =
        PmartPrincipal.current().map(PmartPrincipal::subject).orElse(RequestIdempotency.ANONYMOUS);
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "createQuote", actor),
            body,
            Quote.class,
            () -> calculate(requestId, at, accountId, couponId, items))
        .value();
  }

  public Quote get(String quoteId) {
    return store
        .quote(quoteId)
        .map(json -> mapper.readValue(json, Quote.class))
        .orElseThrow(() -> PmartException.notFound("QUOTE_NOT_FOUND", "견적이 없습니다."));
  }

  private Quote calculate(
      String requestId,
      @Nullable Instant at,
      @Nullable String accountId,
      @Nullable String couponId,
      List<QuoteItem> items) {
    Set<String> refs = new HashSet<>();
    for (QuoteItem i : items) {
      if (!refs.add(i.lineRef())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            "lineRef " + i.lineRef() + " 가 중복되었습니다.",
            List.of(new PmartException.Detail("items[].lineRef", "DUPLICATE")));
      }
    }
    Instant now = clock.instant();
    Instant priceAt = at == null ? now : at;
    Map<String, List<PriceVersion>> versions =
        store.versions(items.stream().map(QuoteItem::sku).distinct().toList());

    List<PmartException.Detail> missing = new ArrayList<>();
    List<PriceVersion> chosen = new ArrayList<>();
    for (QuoteItem i : items) {
      Optional<PriceVersion> v =
          PriceRules.effectiveAt(versions.getOrDefault(i.sku(), List.of()), priceAt);
      if (v.isEmpty()) {
        missing.add(new PmartException.Detail(i.sku(), "PRICE_NOT_SET"));
        continue;
      }
      chosen.add(v.get());
    }
    if (!missing.isEmpty()) {
      throw new PmartException(
          "PRICE_NOT_SET",
          org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
          "가격이 없는 SKU 가 있습니다. 해당 품목을 빼야 합니다.",
          false,
          missing);
    }
    // 기간 할인(ADR-0082): SKU 마다 가장 큰 1개(promotion 이 고른다), 개당
    Map<String, Long> unitPrices = new java.util.LinkedHashMap<>();
    for (int k = 0; k < items.size(); k++) {
      unitPrices.put(items.get(k).sku(), chosen.get(k).basePrice());
    }
    // 수량 행사(ADR-0090 A5): 걸린 SKU 는 기간 할인 없이 무료 수량만
    Map<String, com.pmart.pricing.infrastructure.PromotionClient.Offer> offers =
        promotion.offers(unitPrices.keySet(), priceAt);
    Map<String, Long> discountable = new java.util.LinkedHashMap<>(unitPrices);
    offers.keySet().forEach(discountable::remove);
    Map<String, com.pmart.pricing.infrastructure.PromotionClient.Discount> discounts =
        promotion.discounts(discountable, priceAt);
    long baseItemAmount = 0;
    long promotionTotal = 0;
    long[] afterPromotion = new long[items.size()];
    long[] promoShare = new long[items.size()];
    int[] freeQty = new int[items.size()];
    int maxVersion = 0;
    for (int k = 0; k < items.size(); k++) {
      QuoteItem i = items.get(k);
      PriceVersion v = chosen.get(k);
      var o = offers.get(i.sku());
      freeQty[k] = o == null ? 0 : PriceRules.freeQuantity(o.buy(), o.free(), i.quantity());
      long amount = PriceRules.lineAmount(v.basePrice(), i.quantity() + freeQty[k]);
      var d = discounts.get(i.sku());
      long promo =
          o != null
              ? PriceRules.lineAmount(v.basePrice(), freeQty[k])
              : d == null
                  ? 0
                  : Math.min(amount, PriceRules.lineAmount(d.unitDiscount(), i.quantity()));
      promoShare[k] = promo;
      afterPromotion[k] = amount - promo;
      baseItemAmount = Math.addExact(baseItemAmount, amount);
      promotionTotal += promo;
      maxVersion = Math.max(maxVersion, v.version());
    }
    long itemAmount = baseItemAmount - promotionTotal;
    // 장바구니 쿠폰(ADR-0082): 기간 할인 뒤·쿠폰 전 상품액으로 판정·계산, 품목에 비례 안분
    CouponResult coupon = null;
    long[] couponShare = new long[items.size()];
    if (couponId != null && !couponId.isBlank()) {
      if (accountId == null || accountId.isBlank()) {
        coupon = new CouponResult(couponId, false, "NOT_OWNER", 0, null, null);
      } else {
        var c = promotion.coupon(couponId, accountId, itemAmount, priceAt);
        coupon =
            new CouponResult(
                couponId,
                c.applicable(),
                c.reason(),
                c.discountAmount(),
                c.promotionId(),
                c.name());
        if (c.applicable()) {
          couponShare = PriceRules.allocate(c.discountAmount(), afterPromotion);
        }
      }
    }
    long couponTotal = 0;
    List<QuoteLine> lines = new ArrayList<>();
    for (int k = 0; k < items.size(); k++) {
      QuoteItem i = items.get(k);
      PriceVersion v = chosen.get(k);
      int total = i.quantity() + freeQty[k];
      long amount = PriceRules.lineAmount(v.basePrice(), total);
      long share = promoShare[k] + couponShare[k];
      couponTotal += couponShare[k];
      var d = discounts.get(i.sku());
      var o = offers.get(i.sku());
      lines.add(
          new QuoteLine(
              i.lineRef(),
              i.sku(),
              total,
              v.basePrice(),
              v.version(),
              amount,
              share,
              amount - share,
              promoShare[k],
              couponShare[k],
              o != null
                  ? (freeQty[k] > 0 ? o.promotionId() : null)
                  : (d == null || promoShare[k] == 0 ? null : d.promotionId()),
              i.quantity(),
              freeQty[k],
              o == null ? null : o.promotionId(),
              o == null ? null : o.name(),
              o == null ? 0 : o.buy(),
              o == null ? 0 : o.free()));
    }
    List<String> stages = new ArrayList<>(List.of("BASE"));
    if (java.util.Arrays.stream(freeQty).anyMatch(f -> f > 0)) {
      stages.add("QUANTITY_OFFER");
    }
    boolean periodDiscount = false;
    for (int k = 0; k < items.size(); k++) {
      periodDiscount |= promoShare[k] > 0 && !offers.containsKey(items.get(k).sku());
    }
    if (periodDiscount) {
      stages.add("PROMOTION");
    }
    if (couponTotal > 0) {
      stages.add("CART_COUPON");
    }
    Quote quote =
        new Quote(
            "QT-" + UUID.randomUUID(),
            requestId,
            maxVersion,
            lines,
            itemAmount,
            promotionTotal + couponTotal,
            stages,
            priceAt,
            now,
            baseItemAmount,
            promotionTotal,
            couponTotal,
            coupon);
    store.insertQuote(quote.quoteId(), requestId, mapper.writeValueAsString(quote), now);
    return quote;
  }
}
