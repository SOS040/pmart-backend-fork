package com.pmart.product.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.product.domain.PurchaseLimit.Line;
import java.util.List;
import org.junit.jupiter.api.Test;

class PurchaseLimitAndSaleStateTest {

  @Test
  void eFourPlusBSixPackIsTenUnitsAndPasses() {
    // Commerce 2장 예: 같은 기초상품 E 4개 + B 6입 1팩 = 구성 낱개 10
    var lines = List.of(new Line("E-00000001", "BP-1", 1, 4), new Line("B-00000002", "BP-1", 6, 1));
    assertThat(PurchaseLimit.check(lines, 10)).isEmpty();
  }

  @Test
  void overLimitIsReportedPerBaseProduct() {
    var lines =
        List.of(
            new Line("E-00000001", "BP-1", 1, 5),
            new Line("B-00000002", "BP-1", 6, 1),
            new Line("E-00000003", "BP-2", 1, 10));
    var v = PurchaseLimit.check(lines, 10);
    assertThat(v).hasSize(1);
    assertThat(v.get(0).baseProductId()).isEqualTo("BP-1");
    assertThat(v.get(0).totalUnits()).isEqualTo(11);
    assertThat(v.get(0).skus()).containsExactly("E-00000001", "B-00000002");
  }

  @Test
  void onlySaleStopRequiresApprovalAndMapsToEventStates() {
    assertThat(SaleStateRules.requiresApproval("SALE_STOPPED")).isTrue();
    assertThat(SaleStateRules.requiresApproval("TEMPORARILY_SOLD_OUT")).isFalse();
    assertThat(SaleStateRules.requiresApproval("ON_SALE")).isFalse();
    assertThat(SaleStateRules.eventState("TEMPORARILY_SOLD_OUT")).isEqualTo("TEMP_OUT");
    assertThat(SaleStateRules.eventState("SALE_STOPPED")).isEqualTo("DISCONTINUED");
    assertThat(SaleStateRules.eventState("ON_SALE")).isEqualTo("ON_SALE");
  }
}
