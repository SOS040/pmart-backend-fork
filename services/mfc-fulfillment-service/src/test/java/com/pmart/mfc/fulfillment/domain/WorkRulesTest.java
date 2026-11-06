package com.pmart.mfc.fulfillment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.fulfillment.domain.WorkRules.Line;
import com.pmart.mfc.fulfillment.domain.WorkRules.ReservationPart;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkRulesTest {

  static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");

  @Test
  void retryableOnlyInventoryMismatchAndOperationRestricted() {
    assertThat(WorkRules.retryable("INVENTORY_MISMATCH")).isTrue();
    assertThat(WorkRules.retryable("OPERATION_RESTRICTED")).isTrue();
    assertThat(WorkRules.retryable("CAPACITY_EXCEEDED")).isFalse();
    assertThat(WorkRules.retryable("LOT_INELIGIBLE")).isFalse();
    assertThat(WorkRules.retryable("ALREADY_STOPPED")).isFalse();
  }

  @Test
  void remainingSubtractsStoppedAndDropsEmptyLines() {
    List<Line> req = List.of(new Line("L1", "A", 3), new Line("L2", "B", 2));
    assertThat(WorkRules.remaining(req, Map.of("L1", 1, "L2", 2)))
        .containsExactly(new Line("L1", "A", 2));
    assertThat(WorkRules.remaining(req, Map.of("L1", 5, "L2", 2))).isEmpty();
  }

  @Test
  void reservationMustBeConfirmedAndCoverEachSku() {
    List<Line> need =
        List.of(new Line("L1", "A", 2), new Line("L2", "A", 1), new Line("L3", "B", 1));
    List<ReservationPart> ok =
        List.of(
            new ReservationPart("A", 2, "CONFIRMED"),
            new ReservationPart("A", 1, "CONFIRMED"),
            new ReservationPart("B", 1, "CONFIRMED"));
    assertThat(WorkRules.reservationCovers("CONFIRMED", ok, need)).isTrue();
    // 예약 상태가 확정이 아니면 덮지 못한다
    assertThat(WorkRules.reservationCovers("RELEASED", ok, need)).isFalse();
    assertThat(WorkRules.reservationCovers(null, List.of(), need)).isFalse();
    // 조각 하나가 막혔으면(BLOCKED) 그 수량은 없다
    List<ReservationPart> blocked =
        List.of(
            new ReservationPart("A", 2, "CONFIRMED"),
            new ReservationPart("A", 1, "BLOCKED"),
            new ReservationPart("B", 1, "CONFIRMED"));
    assertThat(WorkRules.reservationCovers("CONFIRMED", blocked, need)).isFalse();
  }

  @Test
  void pickStartImmediateScheduledAndConfigRequired() {
    // 즉시 배송(슬롯 없음) → 바로 READY
    assertThat(WorkRules.pickStart(null, null, NOW).state()).isEqualTo("READY");
    // 예약 주문: 슬롯 시작 3시간 뒤, N=90분 → 1시간 30분 뒤 READY
    Instant slot = NOW.plus(Duration.ofHours(3));
    WorkRules.PickStart p = WorkRules.pickStart(slot, Duration.ofMinutes(90), NOW);
    assertThat(p.state()).isEqualTo("WAITING");
    assertThat(p.readyAt()).isEqualTo(NOW.plus(Duration.ofMinutes(90)));
    assertThat(p.holdReason()).isNull();
    // 준비 시각이 이미 지났으면 READY
    assertThat(WorkRules.pickStart(NOW.plusSeconds(600), Duration.ofMinutes(90), NOW).state())
        .isEqualTo("READY");
    // N 미설정 → CONFIG_REQUIRED 대기(0·즉시로 대체하지 않음)
    WorkRules.PickStart c = WorkRules.pickStart(slot, null, NOW);
    assertThat(c.state()).isEqualTo("WAITING");
    assertThat(c.readyAt()).isNull();
    assertThat(c.holdReason()).isEqualTo("CONFIG_REQUIRED");
  }

  @Test
  void cancelAgainstCapsAtRemaining() {
    Map<String, Integer> left = Map.of("L1", 2, "L2", 1);
    assertThat(
            WorkRules.cancelAgainst(
                left, List.of(new Line("L1", "", 5), new Line("L2", "", 1), new Line("L9", "", 1))))
        .containsExactlyInAnyOrderEntriesOf(Map.of("L1", 2, "L2", 1));
  }
}
