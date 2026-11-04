package com.pmart.assistant.domain;

import java.util.regex.Pattern;

/**
 * 규칙 기반 의도 분류(모델 없음). 단어 하나가 아니라 의도로 나눈다(검토 7).
 *
 * <ul>
 *   <li>실행 명령형("~해줘/~해 주세요/환불해")이고 절차·정책을 묻는 말이 없으면 → OUT_OF_SCOPE (식별자가 없어도 — "재고 줄여줘").
 *   <li>대상 식별자(ORD-/PA-/CLM-/SKU…·긴 번호·"내 주문") + 현재 상태 의도("지금/상태/어디까지/됐어") →
 *       REALTIME_DATA_REQUIRED.
 *   <li>나머지(절차·정책·기준·어떻게/언제/왜/무엇)는 정책 질문. "취소 처리 절차를 알려줘"는 정책 질문이다.
 * </ul>
 *
 * 정책 질문 중 과거·변경 전을 묻는 것은 이력 질문(SUPERSEDED 근거 허용).
 */
public final class QuestionClassifier {

  public enum Intent {
    POLICY,
    REALTIME_DATA_REQUIRED,
    OUT_OF_SCOPE
  }

  public record Classification(Intent intent, boolean history) {}

  /** 업무 식별자: 접두 코드 + 숫자, 6자리 이상 번호, "내/이/그 주문" 같은 특정 대상 지시. */
  private static final Pattern IDENTIFIER =
      Pattern.compile(
          "(?i)(?<![A-Z])(?:ORD|PA|PAY|CLM|SKU|SHP|DLV|TRF|TOUT|PO|RT|RFD|INV|CR|APV)-?[A-Z0-9]*\\d+"
              + "|(?<!\\d)\\d{6,}(?!\\d)"
              + "|(?:내|제|이|그|저|우리)\\s?(?:주문|결제|배송|환불|반품|교환|라이더|재고)");

  private static final String ACTION_STEMS =
      "취소|환불|승인|반려|처리|변경|수정|삭제|등록|차감|배정|배차|재배차|발송|실행|적용|해제|정지|중지|반품|교환|"
          + "줄여|늘려|바꿔|올려|내려|보내|막아|풀어|넣어|빼|지워|돌려|결제";

  /** 실행 명령형: 동작 어간 + "해/시켜" + "줘/주세요…", 또는 문장 끝 "…해(라/요)". 끝이 물음표인 "…해요?"는 명령으로 보지 않는다. */
  private static final Pattern ACTION_IMPERATIVE =
      Pattern.compile(
          "(?:"
              + ACTION_STEMS
              + ")\\s?(?:해|하여|시켜|해서)?\\s?(?:줘|주세요|주십시오|줄래|주라|주실래요|줄 수 있어)"
              + "|(?:"
              + ACTION_STEMS
              + ")\\s?(?:해|하세요|해라|하라|해요|시켜)\\s*[.!]*\\s*$");

  /** 절차·정책을 묻는 말 — 이게 있으면 명령형 꼴이어도 정책 질문(정보 요청 동사는 실행이 아니다). */
  private static final Pattern INFO_CUE =
      Pattern.compile(
          "절차|방법|기준|정책|규칙|조건|흐름|순서|원칙|누가|누구|언제|왜|어떻게|무엇|뭐야|뭔가|무슨|어떤|알려|설명|가르쳐|정리해|요약|"
              + "가능한가|가능해\\?|되나요|인가요|인가\\?|하나요|할 수 있나|해야 하|해야 해|돼\\?|되는지|하는지");

  /** 특정 대상의 현재 상태를 묻는 말. */
  private static final Pattern STATE_CUE =
      Pattern.compile(
          "지금|현재|상태|어디까지|어디야|어디에|됐어|됐나|되었|됐는지|몇 ?개 ?남|남았|진행 ?중|도착했|언제 와|왔어|처리됐|" + "확인해|조회해|보여줘");

  private static final Pattern HISTORY_CUE =
      Pattern.compile("예전|이전에는|이전 정책|과거|변경 전|바뀌기 전|왜 바뀌|원래는|대체되기 전|대체된|폐기된|옛날");

  private QuestionClassifier() {}

  public static Classification classify(String question) {
    String q = RequestHashing.normalize(question);
    boolean identifier = IDENTIFIER.matcher(q).find();
    boolean info = INFO_CUE.matcher(q).find();
    boolean action = ACTION_IMPERATIVE.matcher(q).find() && !info;
    if (action) {
      return new Classification(Intent.OUT_OF_SCOPE, false);
    }
    if (identifier && STATE_CUE.matcher(q).find()) {
      return new Classification(Intent.REALTIME_DATA_REQUIRED, false);
    }
    return new Classification(Intent.POLICY, HISTORY_CUE.matcher(q).find());
  }
}
