package com.pmart.pricing.api;

import com.pmart.pricing.api.generated.model.DisplayDiscount;
import com.pmart.pricing.api.generated.model.ListPrices200Response;
import com.pmart.pricing.api.generated.model.ListPrices200ResponseAsOf;
import com.pmart.pricing.api.generated.model.Price;
import com.pmart.pricing.api.generated.model.PriceNextChange;
import com.pmart.pricing.api.generated.model.PriceVersion;
import com.pmart.pricing.api.generated.model.Quote;
import com.pmart.pricing.api.generated.model.QuoteLine;
import com.pmart.pricing.application.DisplayPrices;
import com.pmart.pricing.application.PriceQueries.CurrentPrice;
import com.pmart.pricing.application.PriceQueries.PriceList;
import com.pmart.pricing.application.QuoteService;
import com.pmart.pricing.infrastructure.PromotionClient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/** 응답 변환. 계약 Money 는 KRW 정수(int32)라 넘치면 예외로 드러낸다. */
final class PricingMapping {

  private PricingMapping() {}

  static Price price(CurrentPrice p) {
    Price out =
        new Price()
            .sku(p.current().sku())
            .basePrice(money(p.current().basePrice()))
            .priceVersion(p.current().version())
            .effectiveFrom(odt(p.current().effectiveFrom()));
    if (p.next() != null) {
      out.nextChange(
          new PriceNextChange()
              .basePrice(money(p.next().basePrice()))
              .effectiveFrom(odt(p.next().effectiveFrom())));
    }
    return out;
  }

  /** 표시용(ADR-0082 4단계) — 할인과 discountAvailable 를 붙인다. */
  static Price shown(
      CurrentPrice p,
      java.util.Map<String, PromotionClient.DisplayDiscount> discounts,
      boolean ok) {
    Price out = price(p).discountAvailable(ok);
    PromotionClient.DisplayDiscount d = discounts.get(p.current().sku());
    if (d != null && d.unitDiscount() > 0) {
      out.discount(
          new DisplayDiscount()
              .promotionId(d.promotionId())
              .name(d.name())
              .unitDiscount(money(d.unitDiscount()))
              .salePrice(money(p.current().basePrice() - d.unitDiscount()))
              .ratePercent(d.ratePercent())
              .endsAt(odt(d.endsAt())));
    }
    return out;
  }

  static ListPrices200Response shownList(DisplayPrices.Shown s) {
    return priceList(s.list())
        .items(s.list().items().stream().map(p -> shown(p, s.discounts(), s.available())).toList());
  }

  static ListPrices200Response priceList(PriceList list) {
    return new ListPrices200Response()
        .items(list.items().stream().map(PricingMapping::price).toList())
        .notSet(list.notSet())
        .asOf(
            new ListPrices200ResponseAsOf()
                .asOf(odt(list.asOf()))
                .freshness(ListPrices200ResponseAsOf.FreshnessEnum.CURRENT));
  }

  static PriceVersion version(com.pmart.pricing.domain.PriceVersion v) {
    return new PriceVersion()
        .sku(v.sku())
        .priceVersion(v.version())
        .basePrice(money(v.basePrice()))
        .effectiveFrom(odt(v.effectiveFrom()))
        .effectiveTo(v.effectiveTo() == null ? null : odt(v.effectiveTo()))
        .approvalId(v.approvalId())
        .state(PriceVersion.StateEnum.fromValue(v.state().name()));
  }

  static Quote quote(QuoteService.Quote q) {
    return new Quote()
        .quoteId(q.quoteId())
        .requestId(q.requestId())
        .priceVersion(q.priceVersion())
        .lines(
            q.lines().stream()
                .map(
                    l ->
                        new QuoteLine()
                            .lineRef(l.lineRef())
                            .sku(l.sku())
                            .quantity(l.quantity())
                            .unitPrice(money(l.unitPrice()))
                            .priceVersion(l.priceVersion())
                            .lineAmount(money(l.lineAmount()))
                            .discountShare(money(l.discountShare()))
                            .netAmount(money(l.netAmount()))
                            .promotionDiscount(money(l.promotionDiscount()))
                            .couponShare(money(l.couponShare()))
                            .promotionId(l.promotionId())
                            // 0.4.0 ADR-0090 — 그 전 견적(0)은 유상 = 총 수량
                            .paidQuantity(l.paidQuantity() == 0 ? l.quantity() : l.paidQuantity())
                            .freeQuantity(l.freeQuantity())
                            .offerPromotionId(l.offerPromotionId())
                            .offerName(l.offerName())
                            .offerBuyQuantity(l.offerBuyQuantity())
                            .offerFreeQuantity(l.offerFreeQuantity()))
                .toList())
        .itemAmount(money(q.itemAmount()))
        .discountTotal(money(q.discountTotal()))
        .baseItemAmount(money(q.baseItemAmount() == 0 ? q.itemAmount() : q.baseItemAmount()))
        .promotionDiscount(money(q.promotionDiscount()))
        .couponDiscount(money(q.couponDiscount()))
        .coupon(
            q.coupon() == null
                ? null
                : new com.pmart.pricing.api.generated.model.QuoteCoupon()
                    .couponId(q.coupon().couponId())
                    .applicable(q.coupon().applicable())
                    .reason(q.coupon().reason())
                    .discountAmount(money(q.coupon().discountAmount()))
                    .promotionId(q.coupon().promotionId())
                    .name(q.coupon().name()))
        .appliedStages(q.appliedStages().stream().map(Quote.AppliedStagesEnum::fromValue).toList())
        .calculatedAt(odt(q.calculatedAt()));
  }

  static int money(long krw) {
    return Math.toIntExact(krw);
  }

  static OffsetDateTime odt(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  static @Nullable Instant instant(@Nullable OffsetDateTime t) {
    return t == null ? null : t.toInstant();
  }
}
