package com.pmart.cart.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.web.PmartException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CartRulesTest {

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
}
