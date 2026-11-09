package com.pmart.customerassistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.customerassistant.domain.CitationVerifier.Violation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 인용 검사. ID 유효성은 확실한 검사, 나머지는 보조 휴리스틱 — 잡는 사례와 <b>못 잡는 사례</b>를 함께 고정한다(못 잡는 사례 = 미검증 한계). */
class CitationVerifierTest {

  private final CitationVerifier verifier = new CitationVerifier(0.5);
  private final Map<String, String> evidence =
      Map.of(
          "D1", "봉투값은 주문당 100원 1회다. 전체 취소 시 전액 환불한다.",
          "D2", "결제 후 주소·연락처는 변경할 수 없다. 예약배달은 상품준비 전까지 시간 변경이 필요하다.");

  @Test
  void supportedAnswerPasses() {
    var r = verifier.verify("봉투값은 주문당 100원 1회입니다 [D1].", List.of("D1"), evidence);
    assertThat(r.valid()).isTrue();
  }

  @Test
  void unknownOrMissingIdsFail() {
    assertThat(verifier.verify("봉투값은 100원", List.of("D9"), evidence).violations())
        .containsExactly(Violation.UNKNOWN_ID);
    assertThat(verifier.verify("봉투값은 100원", List.of(), evidence).violations())
        .containsExactly(Violation.NO_CITATION);
  }

  @Test
  void catchesNumberMismatch() {
    var r = verifier.verify("봉투값은 주문당 200원 1회입니다.", List.of("D1"), evidence);
    assertThat(r.violations()).contains(Violation.NUMBER_MISMATCH);
  }

  @Test
  void catchesNegationMismatch() {
    var r = verifier.verify("예약배달은 상품준비 전까지 시간 변경이 필요하지 않다.", List.of("D2"), evidence);
    assertThat(r.violations()).contains(Violation.NEGATION_MISMATCH);
  }

  @Test
  void catchesUnsupportedSentence() {
    var r = verifier.verify("라이더는 신분증을 확인하고 서명을 받아야 합니다.", List.of("D1"), evidence);
    assertThat(r.violations()).contains(Violation.UNSUPPORTED_SENTENCE);
  }

  /** 한계: 근거의 어휘를 그대로 쓰면서 조건을 빠뜨리거나 다른 근거에 붙이는 잘못은 통과한다 — 생성 충실도는 미검증. */
  @Test
  void limitationConditionDroppedButLexicallySimilarPasses() {
    var r = verifier.verify("전체 취소 시 봉투값은 주문당 100원 1회다.", List.of("D1"), evidence);
    assertThat(r.valid()).as("휴리스틱이 못 잡는 사례 — 의미 결합 오류").isTrue();
  }
}
