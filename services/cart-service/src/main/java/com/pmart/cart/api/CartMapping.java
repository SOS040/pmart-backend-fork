package com.pmart.cart.api;

import com.pmart.cart.api.generated.model.Cart;
import com.pmart.cart.api.generated.model.CartChangeResult;
import com.pmart.cart.api.generated.model.CartItem;
import com.pmart.cart.api.generated.model.CartItemDisplay;
import com.pmart.cart.api.generated.model.CartSourcesInner;
import com.pmart.cart.api.generated.model.CartSourcesInnerAsOf;
import com.pmart.cart.api.generated.model.MergeResult;
import com.pmart.cart.api.generated.model.MergeResultItemsInner;
import com.pmart.cart.application.CartService.ChangeResult;
import com.pmart.cart.application.CartView;
import com.pmart.cart.application.MergeService.MergeView;
import com.pmart.cart.domain.CartRules;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 계약 DTO 변환. */
final class CartMapping {

  private CartMapping() {}

  static Cart cart(CartView.View v) {
    return new Cart()
        .cartId(v.cart().cartId())
        .cartVersion(v.cart().version())
        .ownerType(Cart.OwnerTypeEnum.fromValue(v.cart().ownerType()))
        .accountId(CartRules.MEMBER.equals(v.cart().ownerType()) ? v.cart().ownerId() : null)
        .shoppingAddressId(v.cart().shoppingAddressId())
        .mfcId(v.cart().mfcId())
        .items(v.lines().stream().map(CartMapping::item).toList())
        .selectedItemAmount(v.selectedAmount() == null ? null : Math.toIntExact(v.selectedAmount()))
        .selectedDiscountAmount(
            v.selectedDiscount() == null ? null : Math.toIntExact(v.selectedDiscount()))
        .minOrderAmount(v.minOrderAmount() == null ? null : Math.toIntExact(v.minOrderAmount()))
        .minOrderShortfall(
            v.minOrderShortfall() == null ? null : Math.toIntExact(v.minOrderShortfall()))
        .lastChangedAt(time(v.cart().lastChangedAt()))
        .expiresAt(time(v.expiresAt()))
        .pendingMergeId(null)
        .sources(
            v.sources().stream()
                .map(
                    s ->
                        new CartSourcesInner()
                            .system(s.system())
                            .asOf(
                                new CartSourcesInnerAsOf()
                                    .asOf(time(s.asOf()))
                                    .freshness(
                                        s.available()
                                            ? CartSourcesInnerAsOf.FreshnessEnum.CURRENT
                                            : CartSourcesInnerAsOf.FreshnessEnum.UNAVAILABLE)))
                .toList());
  }

  static CartItem item(CartView.Line l) {
    return new CartItem()
        .cartItemId(l.item().cartItemId())
        .sku(l.item().sku())
        .productId(l.item().productId())
        .quantity(l.item().quantity())
        .selected(l.item().selected())
        .addedAt(time(l.item().addedAt()))
        .display(
            new CartItemDisplay()
                .name(l.name())
                .imageUrl(l.imageUrl() == null ? null : URI.create(l.imageUrl()))
                .unitPrice(l.unitPrice() == null ? null : Math.toIntExact(l.unitPrice()))
                .priceVersion(l.priceVersion())
                .salePrice(l.salePrice() == null ? null : Math.toIntExact(l.salePrice()))
                .discountRatePercent(l.discountRatePercent())
                .discountName(l.discountName())
                .saleState(CartItemDisplay.SaleStateEnum.fromValue(l.saleState()))
                // 담당 MFC 가용 사본(불명은 UNKNOWN, 0 아님). 담기를 막지 않는다
                .availability(CartItemDisplay.AvailabilityEnum.fromValue(l.availability().state()))
                .availableQty(l.availability().exposedQty()));
  }

  static CartChangeResult change(ChangeResult r) {
    return new CartChangeResult()
        .status(CartChangeResult.StatusEnum.fromValue(r.status()))
        .requestId(r.requestId())
        .resultId(r.cartItemId())
        .version(r.cartVersion())
        .occurredAt(time(r.occurredAt()))
        .cartId(r.cartId())
        .cartItemId(r.cartItemId())
        .sku(r.sku())
        .quantity(r.quantity())
        .selected(r.selected())
        .cartVersion(r.cartVersion())
        .appliedRequestId(r.requestId());
  }

  static MergeResult merge(MergeView m) {
    return new MergeResult()
        .mergeId(m.mergeId())
        .state(MergeResult.StateEnum.fromValue(m.state()))
        .targetCartId(m.targetCartId())
        .targetCartVersion(m.targetCartVersion())
        .sourceCartId(m.sourceCartId())
        .sourceCleared(MergeResult.SourceClearedEnum.fromValue(m.sourceCleared()))
        .items(
            m.items().stream()
                .map(
                    i ->
                        new MergeResultItemsInner()
                            .sourceItemId(i.sourceItemId())
                            .sku(i.sku())
                            .requestedQuantity(i.requestedQuantity())
                            .result(MergeResultItemsInner.ResultEnum.fromValue(i.result()))
                            .targetCartItemId(i.targetCartItemId())
                            .mergedQuantity(i.mergedQuantity())
                            .reasonCode(i.reasonCode()))
                .toList())
        .occurredAt(time(m.occurredAt()));
  }

  static OffsetDateTime time(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
