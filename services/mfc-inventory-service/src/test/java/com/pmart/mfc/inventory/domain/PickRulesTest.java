package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.mfc.inventory.domain.PickRules.Part;
import com.pmart.mfc.inventory.domain.PickRules.Step;
import com.pmart.mfc.inventory.domain.PickRules.Take;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 피킹·포장 실적 ↔ 예약 조각(IV-07). */
class PickRulesTest {

  static final List<Part> PARTS =
      List.of(
          new Part("P1", "L1", "LOT-A", true, 2, 0, 0),
          new Part("P2", "L1", "LOT-B", true, 3, 1, 0),
          new Part("P3", "L2", "LOT-A", false, 1, 0, 0));

  @Test
  void pickWithinRemainingOfSameLineAndLot() {
    Map<String, Integer> used = new HashMap<>();
    var r = PickRules.allocate(Step.PICK, "L1", "LOT-B", 2, PARTS, used);
    assertThat(r.error()).isNull();
    assertThat(r.takes()).containsExactly(new Take("P2", 2));
    // 같은 요청 다음 줄은 남은 0 → 초과
    var again = PickRules.allocate(Step.PICK, "L1", "LOT-B", 1, PARTS, used);
    assertThat(again.error()).isEqualTo(PickRules.PICK_QTY_EXCEEDED);
  }

  @Test
  void otherLotUnknownLineAndUnconfirmedAreRejected() {
    assertThat(PickRules.allocate(Step.PICK, "L1", "LOT-Z", 1, PARTS, new HashMap<>()).error())
        .isEqualTo(PickRules.LOT_MISMATCH);
    assertThat(PickRules.allocate(Step.PICK, "L9", "LOT-A", 1, PARTS, new HashMap<>()).error())
        .isEqualTo(PickRules.LINE_NOT_IN_RESERVATION);
    assertThat(PickRules.allocate(Step.PICK, "L2", "LOT-A", 1, PARTS, new HashMap<>()).error())
        .isEqualTo(PickRules.PART_NOT_CONFIRMED);
  }

  @Test
  void packOnlyWhatWasPicked() {
    assertThat(PickRules.allocate(Step.PACK, "L1", "LOT-A", 1, PARTS, new HashMap<>()).error())
        .isEqualTo(PickRules.PICK_QTY_EXCEEDED);
    var ok = PickRules.allocate(Step.PACK, "L1", "LOT-B", 1, PARTS, new HashMap<>());
    assertThat(ok.takes()).containsExactly(new Take("P2", 1));
  }
}
