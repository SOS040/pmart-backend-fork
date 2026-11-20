package com.pmart.promotion.api;

import com.pmart.promotion.api.generated.CouponsApi;
import com.pmart.promotion.api.generated.model.Coupon;
import com.pmart.promotion.api.generated.model.ListCouponCampaigns200Response;
import com.pmart.promotion.api.generated.model.ListMyCoupons200Response;
import com.pmart.promotion.application.CouponService;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CouponsApi — 쿠폰 받기·내 쿠폰함(고객, ADR-0082). */
@RestController
public class CouponsController implements CouponsApi {

  private final CouponService coupons;

  public CouponsController(CouponService coupons) {
    this.coupons = coupons;
  }

  @Override
  public ResponseEntity<ListCouponCampaigns200Response> listCouponCampaigns() {
    return ResponseEntity.ok(
        new ListCouponCampaigns200Response()
            .items(
                new ArrayList<>(
                    coupons.campaigns().stream().map(PromotionMapping::campaign).toList())));
  }

  @Override
  public ResponseEntity<Coupon> downloadCoupon(
      String promotionId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            PromotionMapping.coupon(coupons.download(promotionId, idempotencyKey, xSourceSystem)));
  }

  @Override
  public ResponseEntity<ListMyCoupons200Response> listMyCoupons(@Nullable String status) {
    return ResponseEntity.ok(
        new ListMyCoupons200Response()
            .items(
                new ArrayList<>(
                    coupons.myCoupons(status).stream().map(PromotionMapping::coupon).toList())));
  }
}
