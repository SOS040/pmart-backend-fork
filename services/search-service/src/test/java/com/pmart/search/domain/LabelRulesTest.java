package com.pmart.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 표시사항 값(products-v9, 계약 0.11.0). */
class LabelRulesTest {

  @Test
  void allergenQueryOnlyWhenAsking() {
    assertThat(LabelRules.allergenOf("땅콩 함유")).isEqualTo("땅콩");
    assertThat(LabelRules.allergenOf("땅콩함유")).isEqualTo("땅콩");
    assertThat(LabelRules.allergenOf("계란 알레르기")).isEqualTo("알류");
    assertThat(LabelRules.allergenOf("굴 들어간 제품")).isEqualTo("조개류");
    // 성분 이름만·모르는 낱말은 아님
    assertThat(LabelRules.allergenOf("우유")).isNull();
    assertThat(LabelRules.allergenOf("사과 함유")).isNull();
    assertThat(LabelRules.allergenOf(null)).isNull();
  }

  @Test
  void unknownNutritionOptionMatchesNothing() {
    var t = new LabelRules.Thresholds(5, 2.5, 120, 11, 5.5);
    assertThat(LabelRules.nutritionClause("저당", t)).isNotNull();
    assertThat(LabelRules.nutritionFilter(List.of("저당", "모름"), t).toString())
        .contains("match_none");
  }
}
