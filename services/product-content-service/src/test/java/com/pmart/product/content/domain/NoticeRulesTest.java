package com.pmart.product.content.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NoticeRulesTest {

  static final List<String> NOTICES =
      List.of(
          "식품의 유형: 두부",
          "원산지: 대두(국산)",
          "제조연월일, 소비기한 또는 품질유지기한: 제품 별도 표기일까지",
          "보관 시 주의：냉장 보관",
          "고시 없음 줄",
          " ");

  @Test
  void splitsLabelAndValueAndKeepsLinesWithoutLabel() {
    var info = NoticeRules.legalInfo(NOTICES);
    assertThat(info).hasSize(5);
    assertThat(info.get(0)).isEqualTo(new NoticeRules.Info("식품의 유형", "두부"));
    assertThat(info.get(3)).isEqualTo(new NoticeRules.Info("보관 시 주의", "냉장 보관"));
    assertThat(info.get(4)).isEqualTo(new NoticeRules.Info(null, "고시 없음 줄"));
  }

  @Test
  void originAndExpiryFromTheirLinesOrNull() {
    assertThat(NoticeRules.origin(NOTICES)).isEqualTo("대두(국산)");
    assertThat(NoticeRules.expiryGuide(NOTICES)).isEqualTo("제품 별도 표기일까지");
    assertThat(NoticeRules.origin(List.of("판매자: 피마트"))).isNull();
    assertThat(NoticeRules.expiryGuide(List.of("유통기한: "))).isNull();
    assertThat(NoticeRules.expiryGuide(List.of("유통기한: 수령일 포함 3일"))).isEqualTo("수령일 포함 3일");
  }
}
