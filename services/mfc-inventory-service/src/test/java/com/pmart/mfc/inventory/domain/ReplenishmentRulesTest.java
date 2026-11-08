package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReplenishmentRulesTest {

  @Test
  void documentAcceptanceExample() {
    // MFC 문서 IV-04 수용 기준: C2·min2·target10·U3·S8·V6·u1 → 5
    assertThat(ReplenishmentRules.orderQty(2, 2, 10, 3, 8, 6, 1)).isEqualTo(5);
  }

  @Test
  void onlyAtOrBelowMinAndRoundedDownToUnit() {
    assertThat(ReplenishmentRules.orderQty(3, 2, 10, 0, 100, 100, 1)).isZero(); // 최소 초과
    assertThat(ReplenishmentRules.orderQty(0, 2, 10, 0, 100, 100, 4)).isEqualTo(8); // 10 → 단위 4
    assertThat(ReplenishmentRules.orderQty(0, 2, 10, 0, 3, 100, 4)).isZero(); // 출발 3 < 단위
    assertThat(ReplenishmentRules.orderQty(0, 2, 10, 10, 100, 100, 1)).isZero(); // 진행 중으로 충분
    assertThat(ReplenishmentRules.orderQty(0, 2, 10, 0, 100, -5, 1)).isZero(); // 공간 없음
  }
}
