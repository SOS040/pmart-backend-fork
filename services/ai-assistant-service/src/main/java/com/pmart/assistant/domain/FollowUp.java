package com.pmart.assistant.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 같은 채팅 세션의 이어 묻기(ADR-0084, 사용자 2026-11-20 "앞 대화를 반영") — 규칙 기반, 모델 없음.
 *
 * <p>검색어: 이어지는 질문("그럼 반품은?", "그건 누가 승인해?", 짧은 질문)이면 직전 질문을 앞에 붙여 검색한다. 주제가 바뀐 완결 질문은 그대로 검색한다 — 다른
 * 주제의 앞 질문이 검색을 흐리지 않게. 답변 생성에는 앞 대화를 늘 "근거가 아닌 참고"로 넘긴다(생성기 규칙 9).
 */
public final class FollowUp {

  /** 앞 대화를 가리키는 말. */
  private static final Pattern CUE =
      Pattern.compile(
          "^(?:그럼|그러면|그렇다면|그런데|근데|그리고|또|또는|아니면|만약|그 경우|그때|그건|그거|그것|그게|이건|이거|이것|이게|거기|그쪽|그 다음|다음은)"
              + "|(?:그건|그거|그것|그게|이건|이거|이것|이게|그 경우|그때|위의|앞의|방금)");

  /** 이보다 짧은 질문은 혼자로는 뜻이 모자랄 수 있다고 본다. */
  private static final int SHORT = 12;

  private FollowUp() {}

  /** 앞 대화를 가리키는 질문인가. */
  public static boolean isFollowUp(String question) {
    String q = RequestHashing.normalize(question);
    return !q.isEmpty() && (q.length() < SHORT || CUE.matcher(q).find());
  }

  /** 검색에 쓸 질문. 앞 질문이 없거나 이어지는 질문이 아니면 그대로. */
  public static String retrievalQuery(String question, List<String> previousQuestions) {
    if (previousQuestions.isEmpty() || !isFollowUp(question)) {
      return question;
    }
    return RequestHashing.normalize(previousQuestions.getLast())
        + " "
        + RequestHashing.normalize(question);
  }
}
