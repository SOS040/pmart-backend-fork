package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MoveRulesTest {

  static String check(
      boolean fromHt,
      boolean toHt,
      int qty,
      int pickable,
      int available,
      Map<String, Boolean> shelves,
      int remainingAtFrom,
      int destAfter,
      int capacity) {
    return MoveRules.rejection(
        "A",
        "B",
        fromHt,
        toHt,
        qty,
        pickable,
        available,
        null,
        null,
        null,
        shelves,
        remainingAtFrom,
        destAfter,
        capacity);
  }

  static String category(String toCategory, List<String> paths, Boolean shippable) {
    return MoveRules.rejection(
        "A",
        "B",
        false,
        false,
        1,
        5,
        5,
        toCategory,
        paths,
        shippable,
        Map.of("A", false),
        0,
        1,
        20);
  }

  @Test
  void wholeMoveToAMiscShelfIsAllowed() {
    assertThat(check(false, false, 10, 10, 10, Map.of("A", false), 0, 10, 20)).isNull();
  }

  @Test
  void quantityIsLimitedByPickableAndLotAvailability() {
    assertThat(check(false, false, 5, 4, 10, Map.of(), 0, 5, 20))
        .isEqualTo(MoveRules.MOVE_QTY_EXCEEDS_PICKABLE);
    assertThat(check(false, false, 5, 10, 4, Map.of(), 0, 5, 20))
        .isEqualTo(MoveRules.MOVE_QTY_EXCEEDS_AVAILABLE);
  }

  @Test
  void categoryShelvesTakeOnlyTheirCategoryAndShippableLots() {
    assertThat(category("SNACK", List.of("FOOD/SNACK/CHIPS"), true)).isNull(); // 하위 카테고리 포함
    assertThat(category("SNACK", List.of("FOOD/DRINK"), true))
        .isEqualTo(MoveRules.CATEGORY_MISMATCH);
    assertThat(category("SNACK", null, true)).isEqualTo(MoveRules.SKU_CATEGORY_UNKNOWN);
    assertThat(category("SNACK", List.of("FOOD/SNACK"), false))
        .isEqualTo(MoveRules.LOT_NOT_SHIPPABLE);
    // 기타 칸(카테고리 없음)은 아무 상품·부적격 LOT 도 받는다
    assertThat(category(null, null, false)).isNull();
    // 기준이 없어 판정 못 하는 LOT(null)은 막지 않는다
    assertThat(category("SNACK", List.of("FOOD/SNACK"), null)).isNull();
  }

  @Test
  void oneShelfPerSkuExceptHighTurnover() {
    assertThat(check(false, false, 1, 5, 5, Map.of("A", false, "C", false), 0, 1, 20))
        .isEqualTo(MoveRules.SKU_ALREADY_SHELVED);
    assertThat(check(false, false, 1, 5, 5, Map.of("A", false), 4, 1, 20))
        .isEqualTo(MoveRules.SKU_SPLIT_NOT_ALLOWED);
    assertThat(check(false, true, 1, 5, 5, Map.of("A", false, "C", false), 4, 1, 20)).isNull();
    assertThat(check(true, false, 1, 5, 5, Map.of("A", true), 4, 1, 20)).isNull();
    assertThat(check(false, false, 5, 5, 5, Map.of("A", false, "H", true), 0, 5, 20)).isNull();
  }

  @Test
  void capacityCountsWhatIsThereAndWhatIsComing() {
    assertThat(check(false, true, 3, 5, 5, Map.of(), 2, 21, 20))
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
