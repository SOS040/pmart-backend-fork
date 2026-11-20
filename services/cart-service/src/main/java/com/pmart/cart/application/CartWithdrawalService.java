package com.pmart.cart.application;

import com.pmart.cart.domain.CartRules;
import com.pmart.cart.infrastructure.CartStore;
import com.pmart.common.messaging.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 고객 탈퇴 완료(ADR-0077, customer {@code CustomerWithdrawalCompleted} — commerce.customer.v1): 그
 * 회원(MEMBER = accountId)의 장바구니를 진행 중·만료 상관없이 품목까지 모두 지운다. 재가입은 새 계정이라 장바구니를 복원하지 않는다. InboxGuard
 * 트랜잭션 안에서 부른다. 멱등 — 다시 와도 지울 것이 없을 뿐. 사건(CartItemChangeApplied)은 내지 않는다(고객 변경이 아님).
 */
@Service
public class CartWithdrawalService {

  private static final Logger log = LoggerFactory.getLogger(CartWithdrawalService.class);

  private final CartStore store;

  public CartWithdrawalService(CartStore store) {
    this.store = store;
  }

  public int onWithdrawalCompleted(EventEnvelope e) {
    String accountId = e.payload().path("accountId").asString("");
    if (accountId.isBlank()) {
      return 0;
    }
    int n = store.deleteOwnerCarts(CartRules.MEMBER, accountId);
    if (n > 0) {
      log.info("고객 탈퇴 완료 — 회원 장바구니 {}개 지움", n);
    }
    return n;
  }
}
