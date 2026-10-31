package com.pmart.cart.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.web.PmartException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CartRulesTest {

  @Test
  void availabilityFromMfcStock() {
    assertThat(CartRules.availability(5, false, 1).state()).isEqualTo("NOT_CARRIED");
    assertThat(CartRules.availability(null, true, 1)).isEqualTo(CartRules.Availability.UNKNOWN);
    assertThat(CartRules.availability(0, true, 1).state()).isEqualTo("SOLD_OUT");
    assertThat(CartRules.availability(-1, true, 1).state()).isEqualTo("SOLD_OUT");
    CartRules.Availability low = CartRules.availability(2, true, 5);
    assertThat(low.state()).isEqualTo("LOW");
    assertThat(low.exposedQty()).as("살 수 있는 최대 수량").isEqualTo(2);
    CartRules.Availability enough = CartRules.availability(5, true, 5);
    assertThat(enough.state()).isEqualTo("AVAILABLE");
    assertThat(enough.exposedQty()).as("잔여 수량 노출 임계값 미정 — 비공개").isNull();
  }

  @Test
  void expiresThirtyDaysAfterLastChange() {
    Instant last = Instant.parse("2026-10-01T00:00:00Z");
    Duration d = Duration.ofDays(30);
    assertThat(CartRules.expired(last, d, Instant.parse("2026-10-30T23:59:59Z"))).isFalse();
    assertThat(CartRules.expired(last, d, Instant.parse("2026-10-31T00:00:00Z"))).isTrue();
  }

  @Test
  void expectedVersionBodyAndHeaderMustAgree() {
    assertThat(CartRules.expectedVersion(3, null)).isEqualTo(3);
    assertThat(CartRules.expectedVersion(3, 3)).isEqualTo(3);
    assertThatThrownBy(() -> CartRules.expectedVersion(3, 4)).isInstanceOf(PmartException.class);
    assertThatThrownBy(() -> CartRules.expectedVersion(null, 4)).isInstanceOf(PmartException.class);
  }

  @Test
  void mergeState() {
    assertThat(CartRules.mergeState(2, 2)).isEqualTo("MERGE_CONFIRMED");
    assertThat(CartRules.mergeState(1, 2)).isEqualTo("MERGE_PARTIAL");
    assertThat(CartRules.mergeState(0, 2)).isEqualTo("MERGE_REJECTED");
  }

  @Test
  void remainingAfterOrderRemovesOnlyOrderedQuantity() {
    assertThat(CartRules.remainingAfterOrder(3, 3)).isZero(); // 주문분 그대로 → 삭제
    assertThat(CartRules.remainingAfterOrder(5, 3)).isEqualTo(2); // 주문 뒤 더 담은 2개는 남긴다
    assertThat(CartRules.remainingAfterOrder(2, 3)).isZero(); // 주문 뒤 줄였으면 삭제
    assertThat(CartRules.remainingAfterOrder(2, 0)).isEqualTo(2);
  }
}
