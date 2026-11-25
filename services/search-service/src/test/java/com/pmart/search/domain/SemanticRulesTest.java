package com.pmart.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SemanticRulesTest {

  static final SemanticRules.Source S =
      new SemanticRules.Source("P1", "농심 신라면 120g", List.of("면·즉석", "라면"), "매콤한 국물이 일품인 라면", "국산");

  @Test
  void tagsAreCleanedAndCapped() {
    var m = JsonMapper.builder().build();
    String json =
        "{\"tags\":[\"빨간\",\"매운\",\"신라면\",\"국내산\",\"건강한 맛\",\"\",\"빨간\",\"아주아주아주아주긴낱말입니다\",3,\"야식\"]}";
    assertThat(SemanticRules.parseTags(json, S.name(), m)).containsExactly("빨간", "매운", "야식");
    assertThat(SemanticRules.parseTags("not json", S.name(), m)).isEmpty();
    assertThat(SemanticRules.parseTags(null, S.name(), m)).isEmpty();
  }

  @Test
  void hashChangesWithTextOrModelOnly() {
    String h = SemanticRules.hash(S, "e", "t");
    assertThat(SemanticRules.hash(S, "e", "t")).isEqualTo(h);
    assertThat(SemanticRules.hash(S, "e2", "t")).isNotEqualTo(h);
    var renamed =
        new SemanticRules.Source("P1", "농심 신라면 5입", S.categoryPath(), S.description(), null);
    assertThat(SemanticRules.hash(renamed, "e", "t")).isNotEqualTo(h);
  }

  @Test
  void embeddingTextHasNameCategoryTagsAndDescription() {
    String t = SemanticRules.embeddingText(S, List.of("빨간", "매운"));
    assertThat(t).contains("농심 신라면 120g", "면·즉석 > 라면", "빨간, 매운", "매콤한 국물");
    assertThat(SemanticRules.tagPrompt(S)).contains("상품명: 농심 신라면 120g").contains("JSON");
  }
}
