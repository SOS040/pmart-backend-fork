package com.pmart.customer.api;

import com.pmart.customer.api.generated.WishlistApi;
import com.pmart.customer.api.generated.model.AddWishlistItem200Response;
import com.pmart.customer.api.generated.model.ListWishlistItems200Response;
import com.pmart.customer.api.generated.model.ListWishlistProductIds200Response;
import com.pmart.customer.api.generated.model.WishlistItem;
import com.pmart.customer.application.WishlistService;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 WishlistApi(0.13.0, ADR-0081) — 찜. 업무 규칙은 {@link WishlistService}. */
@RestController
public class WishlistController implements WishlistApi {

  private final WishlistService wishlist;

  public WishlistController(WishlistService wishlist) {
    this.wishlist = wishlist;
  }

  @Override
  public ResponseEntity<AddWishlistItem200Response> addWishlistItem(
      String productId, String idempotencyKey, String xSourceSystem) {
    WishlistService.Added a = wishlist.add(CustomerMapping.currentCustomer(), productId);
    return ResponseEntity.ok(
        new AddWishlistItem200Response()
            .productId(a.productId())
            .addedAt(a.addedAt().atOffset(ZoneOffset.UTC))
            .total(a.total()));
  }

  @Override
  public ResponseEntity<Void> removeWishlistItem(
      String productId, String idempotencyKey, String xSourceSystem) {
    wishlist.remove(CustomerMapping.currentCustomer(), productId);
    return ResponseEntity.noContent().build();
  }

  /** 상품별 찜 수(0.17.0 BR-100 R3, 공개). */
  @Override
  public ResponseEntity<com.pmart.customer.api.generated.model.ListProductWishCounts200Response>
      listProductWishCounts(String productIds) {
    return ResponseEntity.ok(
        new com.pmart.customer.api.generated.model.ListProductWishCounts200Response()
            .items(
                wishlist.counts(productIds).stream()
                    .map(
                        e ->
                            new com.pmart.customer.api.generated.model
                                    .ListProductWishCounts200ResponseItemsInner()
                                .productId(e.getKey())
                                .wishCount(e.getValue()))
                    .toList()));
  }

  @Override
  public ResponseEntity<ListWishlistProductIds200Response> listWishlistProductIds() {
    List<String> ids = wishlist.productIds(CustomerMapping.currentCustomer());
    return ResponseEntity.ok(
        new ListWishlistProductIds200Response().productIds(ids).total(ids.size()));
  }

  @Override
  public ResponseEntity<ListWishlistItems200Response> listWishlistItems(
      Integer page, Integer size) {
    WishlistService.Page p =
        wishlist.page(
            CustomerMapping.currentCustomer(), page == null ? 0 : page, size == null ? 20 : size);
    return ResponseEntity.ok(
        new ListWishlistItems200Response()
            .items(
                p.items().stream()
                    .map(
                        l ->
                            new WishlistItem()
                                .productId(l.productId())
                                .addedAt(l.addedAt().atOffset(ZoneOffset.UTC))
                                .name(l.name())
                                .imageUrl(l.imageUrl())
                                .price(l.price())
                                .saleState(WishlistItem.SaleStateEnum.fromValue(l.saleState())))
                    .toList())
            .total(p.total())
            .limit(p.limit()));
  }
}
