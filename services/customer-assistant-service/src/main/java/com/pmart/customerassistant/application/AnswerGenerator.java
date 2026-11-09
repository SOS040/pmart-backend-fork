package com.pmart.customerassistant.application;

import com.pmart.customerassistant.domain.RetrievedChunk;
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

  /** 외부 의존(모델) 장애. */
  class DependencyFailure extends RuntimeException {
    public DependencyFailure(String message, @Nullable Throwable cause) {
      super(message, cause);
    }
  }

  /** 근거 없음(C4, 문구는 docs/business/customer-faq/README.md). 뒤에 고객센터 문의 연결(handoff)이 붙는다. */
  String NO_GROUNDING_TEXT = "이 문의는 지금 바로 답변드리기 어려워요. 고객센터에 문의를 남겨 주시면 담당자가 확인한 뒤 답변드릴게요.";
}
