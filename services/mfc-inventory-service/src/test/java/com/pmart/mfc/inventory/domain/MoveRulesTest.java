package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MoveRulesTest {

  static String check(
      boolean fromHt,
      boolean toHt,
      int qty,
      int pickable,
      int available,
      Set<String> destSkus,
      Map<String, Boolean> shelves,
      int remainingAtFrom,
      int destAfter,
      int capacity) {
    return MoveRules.rejection(
        "S1",
        "A",
        "B",
        fromHt,
        toHt,
        qty,
        pickable,
        available,
        destSkus,
        shelves,
        remainingAtFrom,
        destAfter,
        capacity);
  }

  @Test
  void wholeMoveToAnEmptyShelfIsAllowed() {
    assertThat(check(false, false, 10, 10, 10, Set.of(), Map.of("A", false), 0, 10, 20)).isNull();
  }

  @Test
  void quantityIsLimitedByPickableAndLotAvailability() {
    assertThat(check(false, false, 5, 4, 10, Set.of(), Map.of(), 0, 5, 20))
        .isEqualTo(MoveRules.MOVE_QTY_EXCEEDS_PICKABLE);
    assertThat(check(false, false, 5, 10, 4, Set.of(), Map.of(), 0, 5, 20))
        .isEqualTo(MoveRules.MOVE_QTY_EXCEEDS_AVAILABLE);
  }

  @Test
  void oneSkuPerShelfAndOneShelfPerSkuExceptHighTurnover() {
    assertThat(check(false, false, 1, 5, 5, Set.of("S2"), Map.of(), 0, 1, 20))
        .isEqualTo(MoveRules.LOCATION_HAS_OTHER_SKU);
    assertThat(check(false, false, 1, 5, 5, Set.of(), Map.of("A", false, "C", false), 0, 1, 20))
        .isEqualTo(MoveRules.SKU_ALREADY_SHELVED);
    assertThat(check(false, false, 1, 5, 5, Set.of(), Map.of("A", false), 4, 1, 20))
        .isEqualTo(MoveRules.SKU_SPLIT_NOT_ALLOWED);
    // 고회전 도착 칸은 나눠 두기·다른 칸 존재를 허용, 고회전 출발 칸은 남겨도 된다
    assertThat(check(false, true, 1, 5, 5, Set.of(), Map.of("A", false, "C", false), 4, 1, 20))
        .isNull();
    assertThat(check(true, false, 1, 5, 5, Set.of(), Map.of("A", true), 4, 1, 20)).isNull();
    // 다른 고회전 칸에 있는 것은 "이미 진열" 이 아니다
    assertThat(check(false, false, 5, 5, 5, Set.of(), Map.of("A", false, "H", true), 0, 5, 20))
        .isNull();
  }

  @Test
  void capacityCountsWhatIsThereAndWhatIsComing() {
    assertThat(check(false, true, 3, 5, 5, Set.of("S1"), Map.of(), 2, 21, 20))
        .isEqualTo(MoveRules.LOCATION_CAPACITY_EXCEEDED);
  }

  @Test
  void dummySizeIsStableAndCapacityNeedsDimensions() {
    assertThat(SkuSize.dummyFor("E-00000001")).isEqualTo(SkuSize.dummyFor("E-00000001"));
    // 1000×600×400 칸: 작은 것 1,000개, 중간 128개, 큰 것 16개(채움률 0.8)
    assertThat(SkuSize.S.capacity(1000, 600, 400, null)).isEqualTo(1000);
    assertThat(SkuSize.M.capacity(1000, 600, 400, null)).isEqualTo(128);
    assertThat(SkuSize.L.capacity(1000, 600, 400, null)).isEqualTo(16);
    assertThat(SkuSize.L.capacity(1000, 600, 400, 10_000)).isEqualTo(4); // 하중 10kg ÷ 2.5kg
    assertThat(SkuSize.S.capacity(null, 600, 400, null)).isNull();
  }
}
