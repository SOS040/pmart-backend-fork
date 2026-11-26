package com.pmart.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PurchaseCountRulesTest {

  @Test
  void cancelledAndExpiredAreNotPurchases() {
    assertThat(PurchaseCountRules.purchased("CANCELLED")).isFalse();
    assertThat(PurchaseCountRules.purchased("EXPIRED")).isFalse();
    assertThat(PurchaseCountRules.purchased("CONFIRMED")).isTrue();
    assertThat(PurchaseCountRules.purchased("COMPLETED")).isTrue();
    assertThat(PurchaseCountRules.purchased("PARTIALLY_DELIVERED")).isTrue();
  }

  @Test
  void frequentLimits() {
    assertThat(PurchaseCountRules.frequentProblem(180, 2, 20)).isNull();
    assertThat(PurchaseCountRules.frequentProblem(1, 1, 1)).isNull();
    assertThat(PurchaseCountRules.frequentProblem(365, 10, 50)).isNull();
    assertThat(PurchaseCountRules.frequentProblem(0, 2, 20)).contains("windowDays");
    assertThat(PurchaseCountRules.frequentProblem(366, 2, 20)).contains("windowDays");
    assertThat(PurchaseCountRules.frequentProblem(180, 0, 20)).contains("minOrders");
    assertThat(PurchaseCountRules.frequentProblem(180, 11, 20)).contains("minOrders");
    assertThat(PurchaseCountRules.frequentProblem(180, 2, 51)).contains("limit");
  }

  @Test
  void customerMetricsRangeIs366DaysInclusive() {
    LocalDate d = LocalDate.of(2024, 1, 1);
    assertThat(PurchaseCountRules.customerMetricsRangeProblem(d, d.plusDays(365))).isNull();
    assertThat(PurchaseCountRules.customerMetricsRangeProblem(d, d.plusDays(366))).isNotNull();
    assertThat(PurchaseCountRules.customerMetricsRangeProblem(d, d.minusDays(1))).isNotNull();
    assertThat(PurchaseCountRules.customerMetricsRangeProblem(null, d)).isNotNull();
  }
}
