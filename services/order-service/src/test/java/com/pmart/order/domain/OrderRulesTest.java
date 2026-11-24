package com.pmart.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Commerce 8.3 설명용 사례 표를 그대로 확인한다. */
class OrderRulesTest {

  @Test
  void commerceExamples() {
    // 재구매·알뜰 16,000 → 배달팁 3,000 + 봉투 100 = 19,100
    var a = OrderRules.amounts("BUNDLED", 16_000, false, 15_000);
    assertThat(a.tip().applied()).isEqualTo(3_000);
    assertThat(a.total()).isEqualTo(19_100);
    // 재구매·한집 30,000 → 무료(OVER_30000) → 30,100
    var b = OrderRules.amounts("SINGLE", 30_000, false, 15_000);
    assertThat(b.tip().applied()).isZero();
    assertThat(b.tip().reason()).isEqualTo("OVER_30000");
    assertThat(b.total()).isEqualTo(30_100);
    // 첫 구매·한집 15,000 → 무료(FIRST_PURCHASE) → 15,100
    var c = OrderRules.amounts("SINGLE", 15_000, true, 15_000);
    assertThat(c.tip().reason()).isEqualTo("FIRST_PURCHASE");
    assertThat(c.total()).isEqualTo(15_100);
    assertThat(c.minOrderSatisfied()).isTrue();
    // 14,500 → 최소 상품액 미달(부족 500), 7,000 → 8,000 부족
    assertThat(OrderRules.amounts("SINGLE", 14_500, false, 15_000).minOrderShortfall())
        .isEqualTo(500);
    assertThat(OrderRules.amounts("SINGLE", 7_000, false, 15_000).minOrderShortfall())
        .isEqualTo(8_000);
    // 30,000 이상이면 첫 구매여도 사유는 OVER_30000(중복 차감 아님)
    assertThat(OrderRules.amounts("SINGLE", 31_000, true, 15_000).tip().reason())
        .isEqualTo("OVER_30000");
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

  @Test
  void handoverAndDeliveryProgress() {
    var a = new OrderRules.Progress("L1", 3, 3, 0, 0, 0, 0);
    var b = new OrderRules.Progress("L2", 2, 2, 0, 0, 0, 0);
    // 같은 행이 LOT 별로 두 번 와도 합으로
    var half =
        OrderRules.handOver(
            List.of(a, b), List.of(new OrderRules.Change("L1", 1), new OrderRules.Change("L1", 1)));
    assertThat(half.get(0)).isEqualTo(new OrderRules.Progress("L1", 3, 1, 2, 0, 0, 0));
    assertThat(OrderRules.progressStatus("PREPARING", half)).isEqualTo("PARTIALLY_HANDED_OVER");
    var all =
        OrderRules.handOver(
            half, List.of(new OrderRules.Change("L1", 1), new OrderRules.Change("L2", 2)));
    assertThat(OrderRules.progressStatus("PARTIALLY_HANDED_OVER", all)).isEqualTo("IN_DELIVERY");
    // 미이행보다 많이·없는 행 → 거절
    assertThatThrownBy(() -> OrderRules.handOver(all, List.of(new OrderRules.Change("L1", 1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrderRules.handOver(all, List.of(new OrderRules.Change("LX", 1))))
        .isInstanceOf(IllegalArgumentException.class);
    // 일부 전달 → PARTIALLY_DELIVERED, 다 전달 → COMPLETED
    var part = OrderRules.deliver(all, List.of(new OrderRules.Change("L1", 3)));
    assertThat(OrderRules.progressStatus("IN_DELIVERY", part)).isEqualTo("PARTIALLY_DELIVERED");
    var done = OrderRules.deliver(part, List.of(new OrderRules.Change("L2", 2)));
    assertThat(OrderRules.progressStatus("PARTIALLY_DELIVERED", done)).isEqualTo("COMPLETED");
    // 보낼 수량 넘는 전달 → 거절
    assertThatThrownBy(() -> OrderRules.deliver(done, List.of(new OrderRules.Change("L2", 1))))
        .isInstanceOf(IllegalArgumentException.class);
    // 역순: 인계보다 전달이 먼저 와도 받고 COMPLETED, 뒤에 온 인계는 상태를 되돌리지 않는다
    var early =
        OrderRules.deliver(
            List.of(a, b), List.of(new OrderRules.Change("L1", 3), new OrderRules.Change("L2", 2)));
    assertThat(OrderRules.progressStatus("PREPARING", early)).isEqualTo("COMPLETED");
    var late =
        OrderRules.handOver(
            early, List.of(new OrderRules.Change("L1", 3), new OrderRules.Change("L2", 2)));
    assertThat(OrderRules.progressStatus("COMPLETED", late)).isEqualTo("COMPLETED");
    // 출고 전 취소분은 전달할 것에서 빠진다 — 나머지를 다 전달하면 COMPLETED
    var cut = new OrderRules.Progress("L1", 3, 0, 2, 1, 2, 0);
    assertThat(OrderRules.progressStatus("IN_DELIVERY", List.of(cut))).isEqualTo("COMPLETED");
    // 진행 순서 밖(CANCELLED)은 그대로, 인계·전달 없으면 지금 상태
    assertThat(OrderRules.progressStatus("CANCELLED", late)).isEqualTo("CANCELLED");
    assertThat(OrderRules.progressStatus("PREPARING", List.of(a, b))).isEqualTo("PREPARING");
  }

  @Test
  void cancelBeforeShipRules() {
    var a = new OrderRules.LineQty("L1", 3, 3, 0, 0);
    var b = new OrderRules.LineQty("L2", 2, 2, 0, 0);
    // 일부 행만 → 그 행만 바뀌고 주문은 아직 취소 아님
    var partial =
        OrderRules.cancelBeforeShip(List.of(a, b), List.of(new OrderRules.Change("L1", 3)));
    assertThat(partial.get(0)).isEqualTo(new OrderRules.LineQty("L1", 3, 0, 0, 3));
    assertThat(partial.get(1)).isEqualTo(b);
    assertThat(OrderRules.fullyCancelled(partial)).isFalse();
    // 전량 → 취소, 불변식 원수량 = 미이행 + 인계 + 출고 전 취소 유지
    var full = OrderRules.cancelBeforeShip(partial, List.of(new OrderRules.Change("L2", 2)));
    assertThat(OrderRules.fullyCancelled(full)).isTrue();
    assertThat(full)
        .allMatch(
            l ->
                l.originalQty()
                    == l.unfulfilledQty() + l.handedOverQty() + l.cancelledBeforeShipQty());
    // 인계된 수량이 남으면 취소 아님
    assertThat(OrderRules.fullyCancelled(List.of(new OrderRules.LineQty("L1", 3, 0, 1, 2))))
        .isFalse();
    // 미이행보다 많이·없는 행·같은 행 두 번 → 거절
    assertThatThrownBy(
            () -> OrderRules.cancelBeforeShip(List.of(a), List.of(new OrderRules.Change("L1", 4))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> OrderRules.cancelBeforeShip(List.of(a), List.of(new OrderRules.Change("LX", 1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                OrderRules.cancelBeforeShip(
                    List.of(a),
                    List.of(new OrderRules.Change("L1", 1), new OrderRules.Change("L1", 1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 결제창 주문명(BR-35): 첫 이름 있는 품목 + 외 N건, 이름이 없으면 품목 수, 100자 상한. */
  @Test
  void orderNameFromProductNameSnapshots() {
    assertThat(OrderRules.orderName(List.of("국산콩 두부 300g"))).isEqualTo("국산콩 두부 300g");
    assertThat(OrderRules.orderName(List.of("국산콩 두부 300g", "대파 1단", "우유 1L")))
        .isEqualTo("국산콩 두부 300g 외 2건");
    // 첫 품목 이름이 비면 다음 이름 있는 품목, N 은 나머지 품목 수
    assertThat(OrderRules.orderName(java.util.Arrays.asList(null, "대파 1단")))
        .isEqualTo("대파 1단 외 1건");
    // 이름이 하나도 없으면(사본 조회 실패·옛 초안) 품목 수로
    assertThat(OrderRules.orderName(java.util.Arrays.asList(null, " ")))
        .isEqualTo("PMart 장보기 2개 상품");
    assertThat(OrderRules.orderName(java.util.Arrays.asList((String) null)))
        .isEqualTo("PMart 장보기 1개 상품");
    // 100자 상한: 상품명을 줄이고 … 를 붙인다(외 N건은 남긴다)
    String longName = "가".repeat(150);
    String name = OrderRules.orderName(List.of(longName, "대파"));
    assertThat(name).hasSize(OrderRules.ORDER_NAME_MAX).endsWith("… 외 1건");
  }

  /**
   * 전달 실패 재발송(2026-11-24 고침 — 주문 28767701): 첫 배송이 실패한 뒤 재발송은 보낼 수량을 늘리지 않는다. 재발송이 전달되면 COMPLETED, 그
   * 뒤 오배송이 확인되면 오배송 재발송을 다시 보낼 수 있다.
   */
  @org.junit.jupiter.api.Test
  void failureReshipmentReplacesFailedShipmentAndLeavesRoomForMisdeliveryResend() {
    // 원수량 1 — 인계 1 뒤 전달 실패(전달 0)
    var failed = new OrderRules.Progress("L1", 1, 0, 1, 0, 0, 0);
    var r = OrderRules.reship(List.of(failed), List.of(new OrderRules.Change("L1", 1)), true);
    assertThat(r.get(0)).isEqualTo(new OrderRules.Progress("L1", 1, 1, 1, 0, 0, 0));
    assertThat(r.get(0).deliverable()).isEqualTo(1);
    var handed = OrderRules.handOver(r, List.of(new OrderRules.Change("L1", 1)));
    var done = OrderRules.deliver(handed, List.of(new OrderRules.Change("L1", 1)));
    assertThat(OrderRules.progressStatus("IN_DELIVERY", done)).isEqualTo("COMPLETED");
    // 그 재발송이 오배송으로 확인되면 오배송 재발송 1 을 더 보낼 수 있다(한도 = 원 − 취소 − 이미 재발송 = 1)
    var mis = OrderRules.reship(done, List.of(new OrderRules.Change("L1", 1)));
    assertThat(mis.get(0).deliverable()).isEqualTo(2);
    // 이미 전달된 만큼은 실패 재발송으로 다시 보낼 수 없다(한도 = 원 − 취소 − 전달 = 0)
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> OrderRules.reship(done, List.of(new OrderRules.Change("L1", 1)), true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(OrderRules.isFailureReshipment("DF-DLV-1")).isTrue();
    assertThat(OrderRules.isFailureReshipment("CASE-1")).isFalse();
    assertThat(OrderRules.isFailureReshipment(null)).isFalse();
  }

  /** 오배송 재발송(2026-11-16): 미이행 +q·재발송 +q, 한도 = 원수량 − 출고 전 취소 − 이미 재발송. 재발송분까지 전달돼야 COMPLETED. */
  @org.junit.jupiter.api.Test
  void reshipmentAddsDeliverableAndCompletesOnlyAfterResentDelivered() {
    var delivered = new OrderRules.Progress("L1", 2, 0, 2, 0, 2, 0);
    var r = OrderRules.reship(List.of(delivered), List.of(new OrderRules.Change("L1", 2)));
    assertThat(r.get(0)).isEqualTo(new OrderRules.Progress("L1", 2, 2, 2, 0, 2, 2));
    assertThat(r.get(0).deliverable()).isEqualTo(4);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> OrderRules.reship(r, List.of(new OrderRules.Change("L1", 1))))
        .isInstanceOf(IllegalArgumentException.class); // 이미 다 재발송
    assertThat(OrderRules.progressStatus("COMPLETED", r)).isEqualTo("COMPLETED"); // 뒤로 가지 않음
    var handed = OrderRules.handOver(r, List.of(new OrderRules.Change("L1", 2)));
    var done = OrderRules.deliver(handed, List.of(new OrderRules.Change("L1", 2)));
    assertThat(done.get(0).deliveredQty()).isEqualTo(4);
    assertThat(OrderRules.progressStatus("IN_DELIVERY", done)).isEqualTo("COMPLETED");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> OrderRules.deliver(done, List.of(new OrderRules.Change("L1", 1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 반품 수량(O-7, 2026-11-17): 행마다 반품 +q, 한도 = 전달 − 이미 반품, 없는 행·초과는 아무것도 바꾸지 않는다. */
  @Test
  void returnedQtyAddsUpToDeliveredMinusAlreadyReturned() {
    var lines =
        List.of(new OrderRules.ReturnLine("L1", 3, 1), new OrderRules.ReturnLine("L2", 2, 0));
    assertThat(OrderRules.returned(lines, List.of(new OrderRules.Change("L1", 2))))
        .containsExactly(java.util.Map.entry("L1", 3));
    // 같은 행이 둘로 와도 합으로
    assertThat(
            OrderRules.returned(
                lines, List.of(new OrderRules.Change("L2", 1), new OrderRules.Change("L2", 1))))
        .containsExactly(java.util.Map.entry("L2", 2));
    assertThatThrownBy(() -> OrderRules.returned(lines, List.of(new OrderRules.Change("L1", 3))))
        .isInstanceOf(IllegalArgumentException.class); // 전달 3 − 반품 1 = 2 까지
    assertThatThrownBy(() -> OrderRules.returned(lines, List.of(new OrderRules.Change("L9", 1))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> OrderRules.returned(lines, List.of(new OrderRules.Change("L1", 0))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
