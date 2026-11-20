package com.pmart.assistant.application;

import com.pmart.assistant.domain.RetrievedChunk;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 검색 결과 → 답변. 기준선(발췌)과 생성(ChatModel) 두 구현. 장애는 {@link DependencyFailure} 로 던진다(→ 503, 완료 결과 없음).
 */
public interface AnswerGenerator {

  enum Mode {
    GENERATIVE,
    BASELINE_EXTRACTIVE,
    NONE
  }

  /**
   * @param grounded false 면 NO_GROUNDING(근거를 찾지 못함 — 정상 결과)
   * @param used 인용할 청크(서버 메타데이터로 인용을 만든다)
   */
  record Generated(
      boolean grounded,
      Mode mode,
      String text,
      List<RetrievedChunk> used,
      @Nullable String chatModel) {}

  Generated generate(String question, List<RetrievedChunk> ranked, List<RetrievedChunk> companions);

  /** 같은 채팅 세션의 앞 대화 한 턴(ADR-0084). 근거가 아니라 질문의 뜻을 이해하는 참고. */
  record PriorTurn(String question, String answer) {}

  /** 앞 대화를 함께 받는 생성(ADR-0084). 기본은 앞 대화를 쓰지 않는다(발췌 기준선 — 답은 근거 원문이라 앞 대화가 바꿀 것이 없다). */
  default Generated generate(
      String question,
      List<RetrievedChunk> ranked,
      List<RetrievedChunk> companions,
      List<PriorTurn> history) {
    return generate(question, ranked, companions);
  }

  /** 외부 의존(모델) 장애. */
  class DependencyFailure extends RuntimeException {
    public DependencyFailure(String message, @Nullable Throwable cause) {
      super(message, cause);
    }
  }

  String NO_GROUNDING_TEXT = "허용된 문서에서 이 질문을 뒷받침할 근거를 찾지 못했습니다. 확인할 수 없습니다.";
}
