package com.pmart.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pmart.cart.infrastructure.CartStore;
import com.pmart.common.test.EventEnvelopeFixtures;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 고객 탈퇴 완료(ADR-0077) → 회원(MEMBER) 장바구니만 지운다. accountId 가 없으면 아무것도 지우지 않는다. */
class CartWithdrawalServiceTest {

  @Test
  void deletesMemberCartsOfAccount() {
    CartStore store = mock(CartStore.class);
    when(store.deleteOwnerCarts("MEMBER", "CU-1")).thenReturn(2);
    var service = new CartWithdrawalService(store);
    var e =
        EventEnvelopeFixtures.envelope("CustomerWithdrawalCompleted")
            .payload(Map.of("accountId", "CU-1", "completedAt", "2026-11-20T00:00:00Z"))
            .build();
    assertThat(service.onWithdrawalCompleted(e)).isEqualTo(2);
    verify(store).deleteOwnerCarts("MEMBER", "CU-1");
  }

  @Test
  void blankAccountIdDeletesNothing() {
    CartStore store = mock(CartStore.class);
    var e =
        EventEnvelopeFixtures.envelope("CustomerWithdrawalCompleted")
            .payload(Map.of("completedAt", "2026-11-20T00:00:00Z"))
            .build();
    assertThat(new CartWithdrawalService(store).onWithdrawalCompleted(e)).isZero();
    verifyNoInteractions(store);
  }
}
