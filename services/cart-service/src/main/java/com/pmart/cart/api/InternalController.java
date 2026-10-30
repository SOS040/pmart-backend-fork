package com.pmart.cart.api;

import com.pmart.cart.api.generated.InternalApi;
import com.pmart.cart.api.generated.model.GetSelectedItemsInternal200Response;
import com.pmart.cart.api.generated.model.GetSelectedItemsInternal200ResponseItemsInner;
import com.pmart.cart.application.CartService;
import com.pmart.cart.domain.CartRules;
import com.pmart.cart.domain.CartRules.Owner;
import com.pmart.cart.infrastructure.CartStore;
import com.pmart.cart.infrastructure.CartStore.CartRow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — 주문 초안용 선택 품목(서비스 토큰). accountId 가 장바구니 주인이 아니면 403. */
@RestController
public class InternalController implements InternalApi {

  private final CartService carts;
  private final CartStore store;

  public InternalController(CartService carts, CartStore store) {
    this.carts = carts;
    this.store = store;
  }

  @Override
  public ResponseEntity<GetSelectedItemsInternal200Response> getSelectedItemsInternal(
      String cartId, String accountId) {
    CartRow cart = carts.owned(cartId, new Owner(CartRules.MEMBER, accountId));
    return ResponseEntity.ok(
        new GetSelectedItemsInternal200Response()
            .cartId(cart.cartId())
            .cartVersion(cart.version())
            .mfcId(cart.mfcId())
            .items(
                store.items(cartId).stream()
                    .filter(CartStore.ItemRow::selected)
                    .map(
                        i ->
                            new GetSelectedItemsInternal200ResponseItemsInner()
                                .cartItemId(i.cartItemId())
                                .sku(i.sku())
                                .quantity(i.quantity()))
                    .toList()));
  }
}
