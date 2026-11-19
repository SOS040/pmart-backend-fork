package com.pmart.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** BR-32(2026-11-19) — 휴대폰 번호 정리·검사·가림. */
class PhoneRulesTest {

  @Test
  void normalizesAndChecksMobileNumbers() {
    assertThat(ProfileRules.normalizePhone("010-1234-5678")).contains("01012345678");
    assertThat(ProfileRules.normalizePhone(" (011) 123 4567 ")).contains("0111234567");
    assertThat(ProfileRules.normalizePhone("")).isEmpty();
    assertThat(ProfileRules.phoneProblem("010-1234-5678")).isEmpty();
    assertThat(ProfileRules.phoneProblem("")).as("빈 값 = 지우기").isEmpty();
    assertThat(ProfileRules.phoneProblem("02-123-4567")).isPresent();
    assertThat(ProfileRules.phoneProblem("010-12-34")).isPresent();
    assertThat(ProfileRules.phoneProblem("abc")).isPresent();
  }

  @Test
  void masksMiddleDigits() {
    assertThat(ProfileRules.maskPhone("01012345678")).isEqualTo("010-****-5678");
    assertThat(ProfileRules.maskPhone("0111234567")).isEqualTo("011-***-4567");
    assertThat(ProfileRules.maskPhone(null)).isNull();
  }
}
