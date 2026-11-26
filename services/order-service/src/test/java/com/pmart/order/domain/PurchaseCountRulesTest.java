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

  @Test
  void customerIdsDedupeAndLimit() {
    assertThat(PurchaseCountRules.customerIds(" a, b ,a,,")).containsExactly("a", "b");
    assertThat(PurchaseCountRules.customerIds(",")).isNull();
    assertThat(PurchaseCountRules.customerIds(null)).isNull();
    assertThat(PurchaseCountRules.customerIds("x,".repeat(50))).hasSize(1);
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 51; i++) {
      many.append("id").append(i).append(',');
    }
    assertThat(PurchaseCountRules.customerIds(many.toString())).isNull();
  }

  @Test
  void recentAmountSinceIsThreeMonthsBackAtSeoulMidnight() {
    assertThat(PurchaseCountRules.recentAmountSince(LocalDate.of(2026, 11, 26)))
        .isEqualTo(java.time.Instant.parse("2026-08-25T15:00:00Z"));
    assertThat(PurchaseCountRules.recentAmountSince(LocalDate.of(2026, 5, 31)))
        .isEqualTo(java.time.Instant.parse("2026-02-27T15:00:00Z")); // 2-28 0시 KST
  }

  @Test
  void cursorRoundTripAndBadCursor() {
    var c =
        new PurchaseCountRules.Cursor(
            java.time.Instant.parse("2016-05-20T03:00:00.123456Z"), "ACC:1");
    assertThat(PurchaseCountRules.decodeCursor(PurchaseCountRules.encodeCursor(c))).isEqualTo(c);
    assertThat(PurchaseCountRules.decodeCursor("@@bad")).isNull();
    assertThat(PurchaseCountRules.decodeCursor(null)).isNull();
    assertThat(PurchaseCountRules.lastOrderRangeProblem(null, null)).isNotNull();
    assertThat(PurchaseCountRules.lastOrderRangeProblem(null, LocalDate.of(2016, 1, 1))).isNull();
  }
}
