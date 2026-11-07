package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.mfc.inventory.domain.ReservationState.Transition;
import com.pmart.mfc.inventory.domain.StockRules.Balance;
import com.pmart.mfc.inventory.domain.StockRules.Line;
import com.pmart.mfc.inventory.domain.StockRules.ReceiptLine;
import com.pmart.mfc.inventory.domain.StockRules.Shortage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StockRulesTest {

  static final String A = "E-00000001";
  static final String B = "E-00000002";

  @Test
  void sameSkuLinesAreSummedInSkuOrder() {
    var sum = StockRules.sumBySku(List.of(new Line(B, 1), new Line(A, 3), new Line(A, 3)));
    assertThat(sum).containsExactly(Map.entry(A, 6), Map.entry(B, 1));
    assertThatThrownBy(() -> StockRules.sumBySku(List.of(new Line(A, 0))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void availableIsSellableMinusReserved() {
    assertThat(new Balance(A, 5, 0, 5, 4, 3).available()).isEqualTo(1);
    assertThat(Balance.empty(A).available()).isZero();
  }

  @Test
  void tc09DuplicateLinesExceedingAvailableAreRejectedAsAWhole() {
    // H5/E5/R0/A5 에 같은 SKU 두 행 3+3 → 합 6 > 5 → 거절
    var requested = StockRules.sumBySku(List.of(new Line(A, 3), new Line(A, 3)));
    List<Shortage> s = StockRules.shortages(requested, Map.of(A, new Balance(A, 5, 0, 5, 0, 1)));
    assertThat(s).containsExactly(new Shortage(A, 6, 5));
  }

  @Test
  void shortageListsOnlyShortSkusAndMissingRowIsZero() {
    var requested = StockRules.sumBySku(List.of(new Line(A, 4), new Line(B, 1)));
    List<Shortage> s = StockRules.shortages(requested, Map.of(A, new Balance(A, 5, 0, 5, 0, 1)));
    assertThat(s).containsExactly(new Shortage(B, 1, 0));
    assertThat(
            StockRules.shortages(
                StockRules.sumBySku(List.of(new Line(A, 5))),
                Map.of(A, new Balance(A, 5, 0, 5, 0, 1))))
        .isEmpty();
  }

  static ReceiptLine line(String sku, String loc, int n, int q, int p) {
    return new ReceiptLine(sku, null, null, loc, n, q, p);
  }

  @Test
  void receiptValidationNeedsLocationAndPositiveTotal() {
    assertThat(
            StockRules.validateReceipt(
                List.of(line(B, "RCV-1", 2, 1, 1), line(A, "RCV-1", 5, 0, 0))))
        .isEqualTo(9);
    assertThatThrownBy(() -> StockRules.validateReceipt(List.of(line(A, "RCV-1", 0, 0, 0))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StockRules.validateReceipt(List.of(line(A, "RCV-1", -1, 0, 0))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StockRules.validateReceipt(List.of(line(A, null, 1, 0, 0))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void receiptFingerprintCoversLotLocationAndQuantities() {
    var x = List.of(line(A, "RCV-1", 5, 0, 0), line(B, "RCV-1", 1, 0, 0));
    var lot =
        List.of(
            new ReceiptLine(A, "M-LOT-9", java.time.LocalDate.of(2026, 11, 20), "RCV-1", 5, 0, 0),
            line(B, "RCV-1", 1, 0, 0));
    var loc = List.of(line(A, "RCV-2", 5, 0, 0), line(B, "RCV-1", 1, 0, 0));
    var qty = List.of(line(A, "RCV-1", 4, 0, 0), line(B, "RCV-1", 1, 0, 0));
    String fx = StockRules.receiptFingerprint("M", "T", "D", x);
    assertThat(fx).isEqualTo(StockRules.receiptFingerprint("M", "T", "D", List.copyOf(x)));
    assertThat(fx)
        .isNotEqualTo(StockRules.receiptFingerprint("M", "T", "D", lot))
        .isNotEqualTo(StockRules.receiptFingerprint("M", "T", "D", loc))
        .isNotEqualTo(StockRules.receiptFingerprint("M", "T", "D", qty))
        .isNotEqualTo(StockRules.receiptFingerprint("M2", "T", "D", x));
  }

  @Test
  void onlyFullReleaseIsSupported() {
    Map<String, Integer> held = Map.of(A, 4, B, 1);
    assertThat(StockRules.isFullRelease(null, held)).isTrue();
    assertThat(StockRules.isFullRelease(List.of(), held)).isTrue();
    assertThat(
            StockRules.isFullRelease(List.of(new Line(B, 1), new Line(A, 2), new Line(A, 2)), held))
        .isTrue();
    assertThat(StockRules.isFullRelease(List.of(new Line(A, 4)), held)).isFalse();
    assertThat(StockRules.isFullRelease(List.of(new Line(A, 3), new Line(B, 1)), held)).isFalse();
  }

  @Test
  void reservationTransitions() {
    assertThat(ReservationState.HELD.confirm()).isEqualTo(Transition.APPLY);
    assertThat(ReservationState.CONFIRMED.confirm()).isEqualTo(Transition.ALREADY);
    assertThat(ReservationState.EXPIRED.confirm()).isEqualTo(Transition.INVALID);
    assertThat(ReservationState.RELEASED.confirm()).isEqualTo(Transition.INVALID);
    assertThat(ReservationState.HELD.release()).isEqualTo(Transition.APPLY);
    assertThat(ReservationState.CONFIRMED.release()).isEqualTo(Transition.APPLY);
    assertThat(ReservationState.RELEASED.release()).isEqualTo(Transition.ALREADY);
    assertThat(ReservationState.EXPIRED.release()).isEqualTo(Transition.ALREADY);
    assertThat(ReservationState.BLOCKED.confirm()).isEqualTo(Transition.INVALID);
    assertThat(ReservationState.BLOCKED.release()).isEqualTo(Transition.INVALID);
    assertThat(ReservationState.CONSUMED.release()).isEqualTo(Transition.INVALID);
    assertThat(ReservationState.BLOCKED.holdsStock()).isFalse();
  }
}
