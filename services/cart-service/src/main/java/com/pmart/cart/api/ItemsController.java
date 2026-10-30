package com.pmart.cart.api;

import com.pmart.cart.api.generated.ItemsApi;
import com.pmart.cart.api.generated.model.AddCartItemRequest;
import com.pmart.cart.api.generated.model.CartChangeResult;
import com.pmart.cart.api.generated.model.ChangeCartItemRequest;
import com.pmart.cart.application.CartOwners;
import com.pmart.cart.application.CartService;
import com.pmart.cart.domain.CartRules;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ItemsApi — 품목 담기·변경·삭제(절대값)·변경 결과 조회. 변환만 한다. */
@RestController
public class ItemsController implements ItemsApi {

  private final CartService carts;

  public ItemsController(CartService carts) {
    this.carts = carts;
  }

  @Override
  public ResponseEntity<CartChangeResult> addCartItem(
      String cartId,
      String idempotencyKey,
      String xSourceSystem,
      AddCartItemRequest body,
      @Nullable Integer xExpectedVersion) {
    int expected = CartRules.expectedVersion(body.getExpectedVersion(), xExpectedVersion);
    return ResponseEntity.ok(
        CartMapping.change(
            carts.add(
                cartId,
                CartOwners.current(),
                idempotencyKey,
                xSourceSystem,
                body.getSku(),
                body.getQuantity(),
                body.getSelected() == null || body.getSelected(),
                expected)));
  }

  @Override
  public ResponseEntity<CartChangeResult> changeCartItem(
      String cartId,
      String itemId,
      String idempotencyKey,
      String xSourceSystem,
      ChangeCartItemRequest body,
      @Nullable Integer xExpectedVersion) {
    int expected = CartRules.expectedVersion(body.getExpectedVersion(), xExpectedVersion);
    return ResponseEntity.ok(
        CartMapping.change(
            carts.change(
                cartId,
                CartOwners.current(),
                itemId,
                idempotencyKey,
                xSourceSystem,
                body.getQuantity(),
                body.getSelected(),
                expected)));
  }

  @Override
  public ResponseEntity<CartChangeResult> deleteCartItem(
      String cartId,
      String itemId,
      String idempotencyKey,
      String xSourceSystem,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        CartMapping.change(
            carts.delete(
                cartId,
                CartOwners.current(),
                itemId,
                idempotencyKey,
                xSourceSystem,
                xExpectedVersion)));
  }

  @Override
  public ResponseEntity<CartChangeResult> getCartChangeRequest(String cartId, String requestId) {
    return ResponseEntity.ok(
        CartMapping.change(carts.changeResult(cartId, CartOwners.current(), requestId)));
  }
}
