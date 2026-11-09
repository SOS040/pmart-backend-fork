package com.pmart.customerassistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 고객 평가 3회차에서 나온 실제 답으로 만든 시험(C12·C05·N05). */
class CustomerAnswerChecksTest {

  static final String TIP =
      """
      ## 배달팁은 얼마인가요? [확정 정책]

      | 배송 방식 | 배달팁 |
      |---|---|
      | 한집배달 | 4,000원 |
      | 알뜰배달·예약배달 | 3,000원 |

      - 상품 금액이 **30,000원 이상**이거나 **첫 주문**이면 배달팁이 **무료**예요.""";

  @Test
  void brokenNumberIsNotAcceptedAsPartOfARealOne() {
    String ev = "단순 변심 교환: 왕복 배달비 9,000원(가는 길·오는 길 합쳐서)";
    assertThat(CustomerAnswerChecks.numbersWhole("왕복 배달비 9,0 00원이 드요.", ev)).isFalse();
    assertThat(CustomerAnswerChecks.numbersWhole("왕복 배달비 9,000원이 들어요.", ev)).isTrue();
    assertThat(CustomerAnswerChecks.numbersWhole("봉투값은 100원이에요.", "주문마다 100원")).isTrue();
  }

  @Test
  void naming_one_table_row_requires_the_other_rows() {
    assertThat(CustomerAnswerChecks.tableComplete("배달팁은 4,000원이에요.", List.of(TIP))).isFalse();
    assertThat(
            CustomerAnswerChecks.tableComplete("한집배달은 4,000원, 알뜰배달·예약배달은 3,000원이에요.", List.of(TIP)))
        .isTrue();
    // 표가 아닌 부분(무료 조건)만 답하면 검사하지 않는다 — 30,000 은 표 행이 아니다
    assertThat(
            CustomerAnswerChecks.tableComplete("상품 금액이 30,000원 이상이거나 첫 주문이면 무료예요.", List.of(TIP)))
        .isTrue();
  }

  @Test
  void subjectFromQuestionMustBeInEvidence() {
    String ev = "지금은 쿠폰 기능이 없어요. 쿠폰이 생기면 환불 기준을 함께 안내할게요.";
    assertThat(CustomerAnswerChecks.questionTermsInEvidence("선물하기 기능이 있나요?", "선물하기 기능이 없어요.", ev))
        .isFalse();
    assertThat(CustomerAnswerChecks.questionTermsInEvidence("쿠폰을 쓴 주문은요?", "지금은 쿠폰 기능이 없어요.", ev))
        .isTrue();
    // 동사·의문 낱말은 대상이 아니다
    assertThat(CustomerAnswerChecks.terms("환불은 언제 들어와요?")).containsExactly("환불");
  }
}
