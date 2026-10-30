package com.pmart.cart.api;

import com.pmart.cart.api.generated.CartsApi;
import com.pmart.cart.api.generated.model.Cart;
import com.pmart.cart.api.generated.model.CreateOrGetCartRequest;
import com.pmart.cart.application.CartOwners;
import com.pmart.cart.application.CartService;
import com.pmart.cart.application.CartView;
import com.pmart.cart.domain.CartRules.Owner;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CartsApi — 장바구니 열기·조회(회원·비회원 토큰). 변환만 한다. */
@RestController
public class CartsController implements CartsApi {

  private final CartService carts;
  private final CartView view;

  public CartsController(CartService carts, CartView view) {
    this.carts = carts;
    this.view = view;
  }

  @Override
  public ResponseEntity<Cart> createOrGetCart(
      String idempotencyKey,
      String xSourceSystem,
      @Nullable String xDeviceId,
      @Nullable CreateOrGetCartRequest body) {
    Owner owner = CartOwners.current();
    var cart =
        carts.createOrGet(
            owner,
            xDeviceId,
            body == null ? null : body.getShoppingAddressId(),
            body == null ? null : body.getMfcId());
    return ResponseEntity.status(HttpStatus.CREATED).body(CartMapping.cart(view.view(cart)));
  }

  @Override
  public ResponseEntity<Cart> getCart(String cartId) {
    return ResponseEntity.ok(
        CartMapping.cart(view.view(carts.owned(cartId, CartOwners.current()))));
  }
}
