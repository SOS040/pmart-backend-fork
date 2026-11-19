package com.pmart.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** OCTOMO 번호 확인 규칙(2026-11-19): 010 11자리만, 본문 숫자 6자리, 번호 해시는 같은 번호면 같다. */
class PhoneVerificationRulesTest {

  @Test
  void onlyMobile010ElevenDigits() {
    assertThat(PhoneVerificationRules.supported("01012345678")).isTrue();
    assertThat(PhoneVerificationRules.supported("0111234567")).isFalse();
    assertThat(PhoneVerificationRules.supported("010-1234-5678")).isFalse();
    assertThat(PhoneVerificationRules.supported("+821012345678")).isFalse();
    assertThat(PhoneVerificationRules.supported(null)).isFalse();
  }

  @Test
  void codeIsSixDigitsAndHashIsStable() {
    for (int i = 0; i < 200; i++) {
      assertThat(PhoneVerificationRules.newCode()).matches("\\d{6}");
    }
    assertThat(PhoneVerificationRules.phoneHash("01012345678"))
        .isEqualTo(PhoneVerificationRules.phoneHash("01012345678"))
        .isNotEqualTo(PhoneVerificationRules.phoneHash("01012345679"))
        .hasSize(64);
    assertThat(PhoneVerificationRules.remaining(5, 7)).isZero();
  }
}
