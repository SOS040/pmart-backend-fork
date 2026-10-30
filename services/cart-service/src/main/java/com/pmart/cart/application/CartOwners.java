package com.pmart.cart.application;

import com.pmart.cart.domain.CartRules;
import com.pmart.cart.domain.CartRules.Owner;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;

/** 현재 토큰 → 장바구니 주인. 고객 = 회원, 비회원 토큰 = 비회원. 그 밖(운영자·기사)은 403. */
public final class CartOwners {

  private CartOwners() {}

  public static Owner current() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "고객·비회원만 쓸 수 있습니다."));
    return switch (p.type()) {
      case CUSTOMER -> new Owner(CartRules.MEMBER, p.subject());
      case GUEST -> new Owner(CartRules.GUEST, p.subject());
      default -> throw new PmartException(ErrorCode.FORBIDDEN, "고객·비회원만 쓸 수 있습니다.");
    };
  }
}
