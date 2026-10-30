package com.pmart.cart.api;

import com.pmart.cart.api.generated.MergesApi;
import com.pmart.cart.api.generated.model.MergeCartRequest;
import com.pmart.cart.api.generated.model.MergeResult;
import com.pmart.cart.application.CartOwners;
import com.pmart.cart.application.MergeService;
import com.pmart.cart.domain.CartRules;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 MergesApi — 비회원 → 회원 병합(회원 토큰 + X-Guest-Token). 변환만 한다. */
@RestController
public class MergesController implements MergesApi {

  private final MergeService merges;

  public MergesController(MergeService merges) {
    this.merges = merges;
  }

  @Override
  public ResponseEntity<MergeResult> mergeCart(
      String cartId,
      String idempotencyKey,
      String xSourceSystem,
      String xGuestToken,
      MergeCartRequest body,
      @Nullable Integer xExpectedVersion) {
    int expected = CartRules.expectedVersion(body.getExpectedVersion(), xExpectedVersion);
    return ResponseEntity.ok(
        CartMapping.merge(
            merges.merge(
                cartId,
                CartOwners.current(),
                idempotencyKey,
                xSourceSystem,
                xGuestToken,
                body.getSourceCartId(),
                expected,
                body.getItems().stream()
                    .map(
                        i ->
                            new MergeService.Requested(
                                i.getSourceItemId(), i.getSku(), i.getQuantity()))
                    .toList())));
  }

  @Override
  public ResponseEntity<MergeResult> getCartMerge(String cartId, String mergeId) {
    return ResponseEntity.ok(
        CartMapping.merge(merges.result(cartId, CartOwners.current(), mergeId)));
  }
}
