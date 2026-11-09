package com.pmart.customerassistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.customerassistant.domain.QuestionClassifier.Intent;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 고객용 규칙: 요청 제한(C3), 주문 상태 문장(C6), 고객 말투의 실시간 질문 분류. */
class CustomerRulesTest {

  final RateLimit limit = new RateLimit(5, 50);

  @Test
  void minuteLimitWaitsUntilOldestFallsOut() {
    Instant now = Instant.parse("2026-11-09T03:00:30Z");
    var d = limit.decide(now, 5, Instant.parse("2026-11-09T03:00:00Z"), 10);
    assertThat(d.allowed()).isFalse();
    assertThat(d.window()).isEqualTo("MINUTE");
    assertThat(d.retryAfterSeconds()).isEqualTo(30);
    assertThat(limit.decide(now, 4, null, 10).allowed()).isTrue();
  }

  @Test
  void dayLimitResetsAtSeoulMidnight() {
    // 서울 23:00 = UTC 14:00 → 서울 자정까지 1시간
    Instant now = Instant.parse("2026-11-09T14:00:00Z");
    var d = limit.decide(now, 0, null, 50);
    assertThat(d.allowed()).isFalse();
    assertThat(d.window()).isEqualTo("DAY");
    assertThat(d.retryAfterSeconds()).isEqualTo(3600);
    assertThat(RateLimit.dayStart(now)).isEqualTo(Instant.parse("2026-11-08T15:00:00Z"));
    assertThat(limit.decide(now, 0, null, 49).allowed()).isTrue();
  }

  @Test
  void orderSentenceUsesFixedWordingAndWindowOnlyWhileInProgress() {
    var w = new OrderStatusText.Window(LocalDate.of(2026, 11, 9), "13:00", "14:00");
    assertThat(OrderStatusText.sentence("PREPARING", w))
        .isEqualTo(
            "고르신 주문은 지금 '상품 준비 중' 상태예요. 도착 예정 시간은 11월 9일 13:00~14:00이에요. 자세한 진행 상황은 주문 상세에서 볼 수 있어요.");
    assertThat(OrderStatusText.sentence("COMPLETED", w))
        .isEqualTo("고르신 주문은 지금 '배송 완료' 상태예요. 자세한 진행 상황은 주문 상세에서 볼 수 있어요.");
    assertThat(OrderStatusText.label("SOMETHING_NEW")).isEqualTo("확인 중");
  }

  @ParameterizedTest
  @ValueSource(strings = {"주문한 거 언제 와요?", "배송 어디쯤이에요", "왜 아직 안 와요?", "언제 도착해요?", "배송 상태 알려줘"})
  void customerStateQuestionsAreRealtime(String q) {
    assertThat(QuestionClassifier.classify(q).intent()).isEqualTo(Intent.REALTIME_DATA_REQUIRED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"배송 현황은 어떻게 보나요?", "주문 취소는 언제까지 할 수 있나요?", "환불은 언제 되나요?", "봉투값도 돌려받나요?"})
  void howAndPolicyQuestionsAreNotRealtime(String q) {
    assertThat(QuestionClassifier.classify(q).intent()).isEqualTo(Intent.POLICY);
  }

  @Test
  void executionRequestIsOutOfScope() {
    assertThat(QuestionClassifier.classify("지금 바로 환불해 주세요").intent())
        .isEqualTo(Intent.OUT_OF_SCOPE);
  }

  @Test
  void stripTagsRemovesStatusMarkers() {
    assertThat(ExtractiveComposer.stripTags("## 취소 [확정 정책]\n[설계안] 본문")).isEqualTo("## 취소\n 본문");
  }
}
