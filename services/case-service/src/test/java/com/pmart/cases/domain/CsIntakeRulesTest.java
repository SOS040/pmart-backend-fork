package com.pmart.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CsIntakeRulesTest {

  @Test
  void acceptsOrderMatchAndSignedInChat() {
    assertThat(CsIntakeRules.rejection("PHONE", "ORDER", "ORD-1", "ORDER_DETAILS_MATCH", null))
        .isNull();
    assertThat(CsIntakeRules.rejection("CHAT", "ACCOUNT", null, "APP_SIGNED_IN_CHAT", null))
        .isNull();
    assertThat(CsIntakeRules.rejection("EMAIL", "OTHER", null, "OTHER", "가입 이메일 대조")).isNull();
  }

  @Test
  void rejectsChannelsCategoriesAndMissingEvidence() {
    assertThat(CsIntakeRules.rejection("APP", "ORDER", "ORD-1", "ORDER_DETAILS_MATCH", null))
        .contains("PHONE");
    assertThat(CsIntakeRules.rejection("PHONE", "RIDER", "ORD-1", "ORDER_DETAILS_MATCH", null))
        .contains("rider-complaints");
    assertThat(CsIntakeRules.rejection("PHONE", "ORDER", " ", "ORDER_DETAILS_MATCH", null))
        .contains("subjectRef");
    assertThat(CsIntakeRules.rejection("PHONE", "ORDER", null, "APP_SIGNED_IN_CHAT", null))
        .contains("CHAT");
    assertThat(CsIntakeRules.rejection("PHONE", "ORDER", null, "OTHER", "")).contains("note");
  }
}
