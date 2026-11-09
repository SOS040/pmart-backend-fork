package com.pmart.customerassistant.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 고객 답변 추가 검사(2026-11-09 고객 평가 3회차 — 4B 모델이 프롬프트 규칙만으로는 고치지 못한 것). 하나라도 걸리면 생성 답변을 버리고 다시 만들고, 그래도
 * 걸리면 안내 원문 발췌로 간다(GenerativeAnswerGenerator).
 *
 * <ul>
 *   <li>{@link #numbersWhole}: 답의 숫자가 근거에 <b>온전한 수</b>로 있어야 한다("9,0 00원" — "9,0" 은 "9,000" 의 일부라 기존
 *       부분 문자열 검사를 통과했다).
 *   <li>{@link #tableComplete}: 근거 표의 한 행 값을 말했으면 다른 행 값도 말해야 한다("배달팁은 4,000원" — 3,000원 행 빠짐).
 *   <li>{@link #questionTermsInEvidence}: 질문의 대상 낱말을 답에 썼으면 근거에도 있어야 한다("선물하기 기능이 없어요" ← 근거는 "쿠폰
 *       기능이 없어요").
 * </ul>
 */
public final class CustomerAnswerChecks {

  private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
  private static final Pattern WORD = Pattern.compile("[가-힣A-Za-z0-9·]{2,}");

  /** 조사·어미 꼬리(길이 내림차순으로 한 번만 뗀다). */
  private static final String[] PARTICLES = {
    "으로는", "에서는", "이에요", "예요", "으로", "에서", "에게", "까지", "부터", "처럼", "은", "는", "이", "가", "을", "를",
    "도", "만", "의", "에", "로", "와", "과", "요"
  };

  /** 동사·의문 꼬리로 끝나면 대상 낱말이 아니다. */
  private static final Pattern VERB_ENDING =
      Pattern.compile(".*(요|되나|하나|있나|없나|세요|니다|는지|ㄴ지|한가|인가|던가|할|된|한|인)$");

  private static final Set<String> STOP =
      Set.of(
          "언제", "얼마", "어떻게", "무엇", "뭐예", "어디", "어디서", "왜", "있나", "있어", "없어", "되나", "하나", "할", "수",
          "주문", "상품", "고객", "기능", "문의", "지금", "바로", "그냥", "혹시", "정말", "제가", "저희", "우리");

  private CustomerAnswerChecks() {}

  /** 답의 숫자마다 근거에 같은 수가 앞뒤 숫자 없이 있는가. */
  public static boolean numbersWhole(String answer, String evidence) {
    Matcher m = NUMBER.matcher(answer);
    while (m.find()) {
      if (!containsWhole(evidence, m.group())) {
        return false;
      }
    }
    return true;
  }

  /**
   * 근거 원문(마크다운) 표마다: 답이 어떤 행의 숫자 값을 말했으면 숫자 값이 있는 모든 행의 숫자를 말했는가. 표 숫자를 하나도 말하지 않았으면 검사하지 않는다(다른
   * 부분을 답한 것).
   */
  public static boolean tableComplete(String answer, List<String> rawEvidence) {
    for (String raw : rawEvidence) {
      for (List<Set<String>> table : tables(raw)) {
        boolean mentioned =
            table.stream().anyMatch(row -> row.stream().anyMatch(n -> containsWhole(answer, n)));
        if (!mentioned) {
          continue;
        }
        for (Set<String> row : table) {
          if (!row.isEmpty() && row.stream().noneMatch(n -> containsWhole(answer, n))) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private static final Pattern YES_NO_LEAD = Pattern.compile("^\\s*(?:네|예|아니요|아뇨)\\s*[,.!]\\s*");

  /** 답에 물음표 문장이 있는가 — 질문을 되풀이한 답("네, 냉동식품도 반품되나요?", 고객 평가 8회차 C11). 안내 답에는 질문이 필요 없다. */
  public static boolean asksQuestion(String answer) {
    return answer.contains("?") || answer.contains("？");
  }

  /**
   * 맨 앞 "네, "·"아니요, " 를 뗀다 — 4B 모델이 내용과 반대로 "네" 로 시작한다("비회원도 주문할 수 있나요?" → "네, … 주문·결제는 회원만", 고객 평가
   * C23). 뒤 내용은 그대로.
   */
  public static String dropYesNoLead(String answer) {
    return YES_NO_LEAD.matcher(answer).replaceFirst("");
  }

  /** 앞뒤가 숫자로 이어지지 않는 같은 수가 있는가("9,0" 은 "9,000" 에 없다). */
  static boolean containsWhole(String text, String number) {
    return Pattern.compile("(?<![\\d.,])" + Pattern.quote(number) + "(?!\\d|[.,]\\d)")
        .matcher(text)
        .find();
  }

  /** 질문의 대상 낱말 중 답에 나온 것이 근거에도 있는가. */
  public static boolean questionTermsInEvidence(String question, String answer, String evidence) {
    for (String term : terms(question)) {
      if (answer.contains(term) && !termInEvidence(term, evidence)) {
        return false;
      }
    }
    return true;
  }

  /**
   * 낱말이 근거에 있는가 — 그대로 있거나, 합성어면 앞 두 글자가 있고 두 글자 묶음의 2/3 이상이 있으면 있다고 본다("냉동식품" ← "냉장·냉동 같은 신선식품", 고객
   * 평가 6회차 C11). "선물하기" 는 "선물" 이 없어 없다.
   */
  static boolean termInEvidence(String term, String evidence) {
    if (evidence.contains(term)) {
      return true;
    }
    if (term.length() < 3 || !evidence.contains(term.substring(0, 2))) {
      return false;
    }
    int total = term.length() - 1;
    int hit = 0;
    for (int i = 0; i < total; i++) {
      if (evidence.contains(term.substring(i, i + 2))) {
        hit++;
      }
    }
    return hit * 3 >= total * 2;
  }

  static Set<String> terms(String question) {
    Set<String> out = new LinkedHashSet<>();
    Matcher m = WORD.matcher(question);
    while (m.find()) {
      String w = m.group();
      if (VERB_ENDING.matcher(w).matches()) {
        continue;
      }
      for (String p : PARTICLES) {
        if (w.endsWith(p) && w.length() - p.length() >= 2) {
          w = w.substring(0, w.length() - p.length());
          break;
        }
      }
      if (w.length() >= 2 && !STOP.contains(w)) {
        out.add(w);
      }
    }
    return out;
  }

  /** 표마다 행별 숫자 값 집합(머리 행 제외). */
  static List<List<Set<String>>> tables(String raw) {
    List<List<Set<String>>> out = new ArrayList<>();
    String[] lines = raw.split("\n");
    int i = 0;
    while (i < lines.length) {
      if (i + 1 < lines.length
          && row(lines[i])
          && lines[i + 1].replaceAll("[|:\\-\\s]", "").isEmpty()
          && row(lines[i + 1])) {
        List<Set<String>> table = new ArrayList<>();
        int j = i + 2;
        while (j < lines.length && row(lines[j])) {
          Set<String> nums = new LinkedHashSet<>();
          Matcher m = NUMBER.matcher(lines[j]);
          while (m.find()) {
            nums.add(m.group());
          }
          table.add(nums);
          j++;
        }
        if (table.size() > 1) {
          out.add(table);
        }
        i = j;
      } else {
        i++;
      }
    }
    return out;
  }

  private static boolean row(String line) {
    String t = line.strip();
    return t.startsWith("|") && t.endsWith("|") && t.length() > 1;
  }
}
