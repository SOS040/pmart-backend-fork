package com.pmart.storefront.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** BR-19(2026-11-19) — 레퍼런스 예: 1kg 9,990원 → 999원, 500g 5,290원 → 1,058원. */
class UnitPriceTest {

  @Test
  void per100RoundsHalfUpAndNeedsBothValues() {
    assertThat(UnitPrice.of(9990L, 1000)).isEqualTo(999L);
    assertThat(UnitPrice.of(5290L, 500)).isEqualTo(1058L);
    assertThat(UnitPrice.of(5L, 1000)).isEqualTo(1L);
    assertThat(UnitPrice.of(null, 500)).isNull();
    assertThat(UnitPrice.of(1000L, null)).isNull();
    assertThat(UnitPrice.of(1000L, 0)).isNull();
  }
}
