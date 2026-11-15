package com.pmart.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProfileRulesTest {

  @Test
  void nicknameIsTrimmedAndCountedInCharacters() {
    assertThat(ProfileRules.nicknameProblem(" 장보기왕 ")).isEmpty();
    assertThat(ProfileRules.normalizeNickname(" 장보기왕 ")).isEqualTo("장보기왕");
    assertThat(ProfileRules.nicknameProblem("가".repeat(30))).isEmpty();
    assertThat(ProfileRules.nicknameProblem("가".repeat(31))).isPresent();
    // 이모지(서로게이트 쌍)도 한 글자
    assertThat(ProfileRules.nicknameProblem("😀".repeat(30))).isEmpty();
  }

  @Test
  void blankAndControlCharactersAreRejected() {
    assertThat(ProfileRules.nicknameProblem("   ")).isPresent();
    assertThat(ProfileRules.nicknameProblem("줄\n바꿈")).isPresent();
    assertThat(ProfileRules.nicknameProblem("탭\t")).isEmpty(); // 끝의 탭은 strip 으로 사라진다
    assertThat(ProfileRules.nicknameProblem("가\t나")).isPresent();
  }

  @Test
  void reviewAuthorShowsOnlyFirstCharacterOfNickname() {
    assertThat(ProfileRules.maskedAuthor("장보기왕")).isEqualTo("장***");
    assertThat(ProfileRules.maskedAuthor("a")).isEqualTo("a*");
    assertThat(ProfileRules.maskedAuthor("가나다라마바사")).isEqualTo("가****"); // 별표는 4개까지(길이 노출 줄임)
    assertThat(ProfileRules.maskedAuthor("😀웃음")).isEqualTo("😀**");
    assertThat(ProfileRules.maskedAuthor(null)).isEqualTo(ProfileRules.ANONYMOUS_AUTHOR);
    assertThat(ProfileRules.maskedAuthor("  ")).isEqualTo(ProfileRules.ANONYMOUS_AUTHOR);
  }

  @Test
  void onlyActiveAccountsCanChange() {
    assertThat(ProfileRules.changeable("ACTIVE")).isTrue();
    assertThat(ProfileRules.changeable("WITHDRAW_PENDING")).isFalse();
    assertThat(ProfileRules.changeable("WITHDRAW_COMPLETED")).isFalse();
  }
}
