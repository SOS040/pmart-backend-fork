package com.pmart.promotion.api;

import com.pmart.promotion.api.generated.InternalApi;
import com.pmart.promotion.api.generated.model.CouponHold;
import com.pmart.promotion.api.generated.model.CouponQuote;
import com.pmart.promotion.api.generated.model.CouponQuoteRequest;
import com.pmart.promotion.api.generated.model.HoldCouponRequest;
import com.pmart.promotion.api.generated.model.LookupPriceDiscounts200Response;
import com.pmart.promotion.api.generated.model.LookupPriceDiscountsRequest;
import com.pmart.promotion.api.generated.model.PriceDiscount;
import com.pmart.promotion.application.CouponService;
import com.pmart.promotion.application.PriceDiscounts;
import com.pmart.promotion.infrastructure.PromotionStore.PromotionRow;
import java.time.Instant;
import java.util.ArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — 가격 할인 조회·쿠폰 계산·점유(서비스 간, ADR-0082). */
@RestController
public class InternalController implements InternalApi {

  private final PriceDiscounts discounts;
  private final CouponService coupons;

  public InternalController(PriceDiscounts discounts, CouponService coupons) {
    this.discounts = discounts;
    this.coupons = coupons;
  }

  @Override
  public ResponseEntity<LookupPriceDiscounts200Response> lookupPriceDiscounts(
      LookupPriceDiscountsRequest body) {
    Instant at = discounts.asOf(body.getAt() == null ? null : body.getAt().toInstant());
    var results =
        discounts.lookup(
            body.getItems().stream()
                .map(i -> new PriceDiscounts.Item(i.getSku(), i.getUnitPrice()))
                .toList(),
            at);
    LookupPriceDiscounts200Response out =
        new LookupPriceDiscounts200Response()
            .asOf(PromotionMapping.odt(at))
            .items(new ArrayList<>());
    for (PriceDiscounts.Result r : results) {
      PromotionRow p = r.promotion();
      Instant ends =
          p.endedAt() != null && p.endedAt().isBefore(p.endsAt()) ? p.endedAt() : p.endsAt();
      out.addItemsItem(
          new PriceDiscount()
              .sku(r.sku())
              .promotionId(p.promotionId())
              .name(p.name())
              .unitPrice(Math.toIntExact(r.unitPrice()))
              .unitDiscount(Math.toIntExact(r.unitDiscount()))
              .discountedUnitPrice(Math.toIntExact(r.unitPrice() - r.unitDiscount()))
              .ratePercent(r.ratePercent())
              .endsAt(PromotionMapping.odt(ends)));
    }
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<CouponQuote> quoteCoupon(CouponQuoteRequest body) {
    CouponService.Quote q =
        coupons.quote(
            body.getCouponId(),
            body.getAccountId(),
            body.getItemAmount(),
            body.getAt() == null ? null : body.getAt().toInstant());
    return ResponseEntity.ok(
        new CouponQuote()
            .couponId(q.couponId())
            .applicable(q.applicable())
            .reason(q.reason() == null ? null : CouponQuote.ReasonEnum.fromValue(q.reason()))
            .discountAmount(Math.toIntExact(q.discountAmount()))
            .promotionId(q.promotionId())
            .name(q.name()));
  }

  @Override
  public ResponseEntity<CouponHold> holdCoupon(
      String idempotencyKey, String xSourceSystem, HoldCouponRequest body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            PromotionMapping.hold(
                coupons.hold(
                    body.getOrderId(),
                    body.getCouponId(),
                    body.getAccountId(),
                    body.getItemAmount(),
                    body.getAt() == null ? null : body.getAt().toInstant(),
                    idempotencyKey,
                    xSourceSystem)));
  }

  @Override
  public ResponseEntity<CouponHold> getCouponHold(String holdId) {
    return ResponseEntity.ok(PromotionMapping.hold(coupons.getHold(holdId)));
  }

  @Override
  public ResponseEntity<CouponHold> confirmCouponHold(
      String holdId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(PromotionMapping.hold(coupons.confirm(holdId)));
  }

  @Override
  public ResponseEntity<CouponHold> releaseCouponHold(
      String holdId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(PromotionMapping.hold(coupons.release(holdId)));
  }

  @Override
  public ResponseEntity<CouponHold> restoreCouponHold(
      String holdId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(PromotionMapping.hold(coupons.restore(holdId)));
  }
}
