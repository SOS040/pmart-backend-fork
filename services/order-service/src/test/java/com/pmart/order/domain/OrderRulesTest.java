package com.pmart.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Commerce 8.3 설명용 사례 표를 그대로 확인한다. */
class OrderRulesTest {

  @Test
  void commerceExamples() {
    // 재구매·알뜰 16,000 → 배달팁 3,000 + 봉투 100 = 19,100
    var a = OrderRules.amounts("BUNDLED", 16_000, false);
    assertThat(a.tip().applied()).isEqualTo(3_000);
    assertThat(a.total()).isEqualTo(19_100);
    // 재구매·한집 30,000 → 무료(OVER_30000) → 30,100
    var b = OrderRules.amounts("SINGLE", 30_000, false);
    assertThat(b.tip().applied()).isZero();
    assertThat(b.tip().reason()).isEqualTo("OVER_30000");
    assertThat(b.total()).isEqualTo(30_100);
    // 첫 구매·한집 15,000 → 무료(FIRST_PURCHASE) → 15,100
    var c = OrderRules.amounts("SINGLE", 15_000, true);
    assertThat(c.tip().reason()).isEqualTo("FIRST_PURCHASE");
    assertThat(c.total()).isEqualTo(15_100);
    assertThat(c.minOrderSatisfied()).isTrue();
    // 14,500 → 최소 상품액 미달(부족 500), 7,000 → 8,000 부족
    assertThat(OrderRules.amounts("SINGLE", 14_500, false).minOrderShortfall()).isEqualTo(500);
    assertThat(OrderRules.amounts("SINGLE", 7_000, false).minOrderShortfall()).isEqualTo(8_000);
    // 30,000 이상이면 첫 구매여도 사유는 OVER_30000(중복 차감 아님)
    assertThat(OrderRules.amounts("SINGLE", 31_000, true).tip().reason()).isEqualTo("OVER_30000");
    assertThat(OrderRules.tipBase("SINGLE")).isEqualTo(4_000);
    assertThat(OrderRules.tipBase("SCHEDULED")).isEqualTo(3_000);
  }

  @Test
  void weightSplit() {
    // 10,000g 은 분할 없음, 10,001g 은 분할
    assertThat(OrderRules.plannedShipments(List.of(new int[] {5_000, 2}))).isEqualTo(1);
    assertThat(OrderRules.plannedShipments(List.of(new int[] {5_000, 2}, new int[] {1, 1})))
        .isEqualTo(2);
    assertThat(OrderRules.plannedShipments(List.of(new int[] {12_000, 1}))).isEqualTo(-1);
    assertThat(OrderRules.plannedShipments(List.of(new int[] {4_000, 5}))).isEqualTo(3);
  }

  @Test
  void nightTimeIsSeoul() {
    assertThat(OrderRules.nightTime(Instant.parse("2026-10-30T22:59:00Z"))).isTrue(); // 07:59 KST
    assertThat(OrderRules.nightTime(Instant.parse("2026-10-30T23:00:00Z"))).isFalse(); // 08:00 KST
    assertThat(OrderRules.nightTime(Instant.parse("2026-10-30T15:00:00Z"))).isTrue(); // 00:00 KST
  }

  @Test
  void blockersOrderedForPrimaryButton() {
    var ordered =
        OrderRules.ordered(
            List.of(
                new OrderRules.Blocker("SLOT_REQUIRED", null),
                new OrderRules.Blocker("MIN_ORDER_AMOUNT", null)));
    assertThat(ordered.get(0).code()).isEqualTo("MIN_ORDER_AMOUNT");
  }

  @Test
  void confirmationRules() {
    // 주문행 ID 는 사가에 보낸 값과 같아야 한다
    assertThat(OrderRules.orderLineId("ORD-1", 2)).isEqualTo("ORD-1-L2");
    // 첫 구매 혜택은 FIRST_PURCHASE 로 무료가 된 주문만 — 30,000 이상 무료는 혜택을 쓰지 않는다
    assertThat(OrderRules.firstPurchaseApplied("FIRST_PURCHASE")).isTrue();
    assertThat(OrderRules.firstPurchaseApplied("OVER_30000")).isFalse();
    assertThat(OrderRules.firstPurchaseApplied("STANDARD")).isFalse();
    // 승인 금액은 동결 총액과 정확히 같아야 한다(부분·초과 모두 차단)
    assertThat(OrderRules.amountMatches(22_100, 22_100)).isTrue();
    assertThat(OrderRules.amountMatches(22_000, 22_100)).isFalse();
    assertThat(OrderRules.amountMatches(22_200, 22_100)).isFalse();
    // 도착 구간: 서울 2026-11-01 10:00~11:00 → UTC 01:00~02:00, 24:00 은 다음 날 0시
    Instant[] w = OrderRules.windowInstants(java.time.LocalDate.of(2026, 11, 1), "10:00", "11:00");
    assertThat(w[0]).isEqualTo(Instant.parse("2026-11-01T01:00:00Z"));
    assertThat(w[1]).isEqualTo(Instant.parse("2026-11-01T02:00:00Z"));
    assertThat(OrderRules.windowInstants(java.time.LocalDate.of(2026, 11, 1), "23:00", "24:00")[1])
        .isEqualTo(Instant.parse("2026-11-01T15:00:00Z"));
  }
}
