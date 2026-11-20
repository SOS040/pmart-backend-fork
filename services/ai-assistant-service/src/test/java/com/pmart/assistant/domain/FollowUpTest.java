package com.pmart.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FollowUpTest {

  @ParameterizedTest
  @ValueSource(strings = {"그럼 반품은?", "그건 누가 승인해?", "만약 전체 취소하면 어떻게 되나요?", "환불은?", "이거 기준이 뭐예요"})
  void followUpsAreDetected(String q) {
    assertThat(FollowUp.isFollowUp(q)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"무게 10,000g 넘는 주문은 분할 배송하나요?", "상품준비 뒤에 고객센터에서 취소할 수 있나요?"})
  void completeQuestionsAreNotFollowUps(String q) {
    assertThat(FollowUp.isFollowUp(q)).isFalse();
  }

  @Test
  void retrievalQueryPrependsOnlyThePreviousQuestionForFollowUps() {
    List<String> prev = List.of("배달팁은 얼마인가요?", "봉투값은 얼마인가요?");
    assertThat(FollowUp.retrievalQuery("그럼 환불은?", prev)).isEqualTo("봉투값은 얼마인가요? 그럼 환불은?");
    assertThat(FollowUp.retrievalQuery("무게 10,000g 넘는 주문은 분할 배송하나요?", prev))
        .isEqualTo("무게 10,000g 넘는 주문은 분할 배송하나요?");
    assertThat(FollowUp.retrievalQuery("그럼 환불은?", List.of())).isEqualTo("그럼 환불은?");
  }

  @Test
  void sessionChangesRequestHashOnlyWhenGiven() {
    String base = RequestHashing.requestHash("질문", null, null);
    assertThat(RequestHashing.requestHash("질문", null, null, null)).isEqualTo(base);
    assertThat(RequestHashing.requestHash("질문", null, null, "SES-1")).isNotEqualTo(base);
    assertThat(RequestHashing.requestHash("질문", null, null, "SES-1"))
        .isNotEqualTo(RequestHashing.requestHash("질문", null, null, "SES-2"));
  }
}
