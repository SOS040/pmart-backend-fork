package com.pmart.product.content.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 표시사항 값(계약 0.6.0, 2026-11-23). */
class LabelFactsTest {

  @Test
  void acceptsReadValuesAndLegalAllergenNames() {
    assertThat(
            LabelFacts.problems(
                new LabelFacts.Nutrition("G", 206.0, 277.0, null, 13.0, null),
                new LabelFacts.Allergens(List.of("닭고기", "알류"), List.of("땅콩"), "원문")))
        .isEmpty();
    assertThat(LabelFacts.problems(null, null)).isEmpty();
  }

  @Test
  void rejectsWrongUnitNegativeEmptyAndUnknownNames() {
    assertThat(
            LabelFacts.problems(new LabelFacts.Nutrition("KG", 1.0, null, null, null, null), null))
        .hasSize(1);
    assertThat(
            LabelFacts.problems(new LabelFacts.Nutrition("G", -1.0, null, null, null, null), null))
        .hasSize(1);
    assertThat(
            LabelFacts.problems(new LabelFacts.Nutrition("ML", null, null, null, null, null), null))
        .hasSize(1);
    // 계란·굴 같은 다른 이름은 법정 이름(알류·조개류)으로 맞춰서 넣는다
    assertThat(LabelFacts.problems(null, new LabelFacts.Allergens(List.of("계란"), null, null)))
        .hasSize(1);
    // 함유와 같은 시설에 같은 이름
    assertThat(
            LabelFacts.problems(null, new LabelFacts.Allergens(List.of("밀"), List.of("밀"), null)))
        .hasSize(1);
  }

  @Test
  void hashUnchangedWithoutFacts() {
    String before = ContentRules.contentHash("n", null, null, null, "[]", "[]", List.of());
    assertThat(ContentRules.contentHash("n", null, null, null, "[]", "[]", List.of(), null))
        .isEqualTo(before);
    assertThat(ContentRules.contentHash("n", null, null, null, "[]", "[]", List.of(), "{}"))
        .isNotEqualTo(before);
  }
}
