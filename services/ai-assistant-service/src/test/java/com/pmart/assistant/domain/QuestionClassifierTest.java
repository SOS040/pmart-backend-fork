package com.pmart.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.assistant.domain.QuestionClassifier.Intent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 의도 기반 분류(검토 7) — 단어 하나가 아니라 의도로 나뉘는지 긍정·부정 사례로 확인. */
class QuestionClassifierTest {

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        // 정책 질문 — 실행 동사가 들어 있어도 절차·기준을 묻는다
        "취소 처리 절차를 알려줘 | POLICY",
        "재고 몇 개 이하에서 보충하는 정책인가? | POLICY",
        "환불 승인은 누가 하나요? | POLICY",
        "주문 취소는 언제까지 할 수 있어? | POLICY",
        "예약배달 시간 변경 기준이 뭐야 | POLICY",
        "라이더 인계 뒤 취소가 들어오면 어떻게 처리해? | POLICY",
        "ORD-123 같은 주문의 취소 절차를 설명해줘 | POLICY",
        "가격 변경 승인선은 어떻게 되나요? | POLICY",
        "봉투값은 얼마야? | POLICY",
        "결품이 나면 고객에게 어떻게 안내해? | POLICY",
        "반품 회수 흐름 정리해줘 | POLICY",
        // 실행 요청
        "ORD-123을 취소해줘 | OUT_OF_SCOPE",
        "CLM-9 환불해 | OUT_OF_SCOPE",
        "재고 줄여줘 | OUT_OF_SCOPE",
        "PA-77 승인해 주세요 | OUT_OF_SCOPE",
        "SKU12345678 가격 올려줘 | OUT_OF_SCOPE",
        "이 주문 배차해줘 | OUT_OF_SCOPE",
        "ORD-55 반품 처리해줘 | OUT_OF_SCOPE",
        // 실시간 상태
        "ORD-123 지금 어디까지 왔어? | REALTIME_DATA_REQUIRED",
        "내 주문 상태 알려줘 지금 | REALTIME_DATA_REQUIRED",
        "CLM-9 환불 처리됐어? | REALTIME_DATA_REQUIRED",
        "SKU12345678 재고 몇 개 남았어 | REALTIME_DATA_REQUIRED",
        "PAY-1001 결제 현재 상태 확인해줘 | REALTIME_DATA_REQUIRED",
        // 식별자 없이 상태 단어만 — 정책 질문
        "주문 상태는 어떤 것들이 있어? | POLICY",
        "현재 정책상 분할 기준은 몇 g이야? | POLICY",
      })
  void classifiesByIntent(String question, Intent expected) {
    assertThat(QuestionClassifier.classify(question).intent()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "이력 질문: {0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "예전에는 서비스 토큰을 어떻게 발급했어? | true",
        "검색 색인 방식이 왜 바뀌었어? | true",
        "변경 전 설정 저장소 규칙은? | true",
        "서비스 토큰 정책 알려줘 | false",
      })
  void detectsHistoryQuestions(String question, boolean history) {
    var c = QuestionClassifier.classify(question);
    assertThat(c.intent()).isEqualTo(Intent.POLICY);
    assertThat(c.history()).isEqualTo(history);
  }
}
