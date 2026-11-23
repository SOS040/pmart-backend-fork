package com.pmart.customerassistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** CA-2 — case 0.37.0 ProfanityRulesTest 와 같은 규칙인지 같은 예로 본다. */
class ProfanityRulesTest {

  final List<String> words = List.of("나쁜말", "BadWord");

  @Test
  void catchesSpacedPunctuatedAndCaseVariants() {
    assertThat(ProfanityRules.contains(words, "배송이 왜 이래 나쁜말")).isTrue();
    assertThat(ProfanityRules.contains(words, "나 쁜.말 진짜")).isTrue();
    assertThat(ProfanityRules.contains(words, "badword?")).isTrue();
    assertThat(ProfanityRules.contains(words, "나쁜~말♥")).isTrue();
  }

  @Test
  void cleanTextAndEmptyListCatchNothing() {
    assertThat(ProfanityRules.contains(words, "봉투값은 얼마인가요?")).isFalse();
    assertThat(ProfanityRules.contains(List.of(), "나쁜말")).isFalse();
    assertThat(ProfanityRules.contains(List.of(" ", "."), "아무 글")).as("빈 단어는 무시").isFalse();
    assertThat(ProfanityRules.contains(words, (String) null)).isFalse();
  }
}
