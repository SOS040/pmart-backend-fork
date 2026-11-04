package com.pmart.assistant.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 생성 답변의 인용 검사.
 *
 * <ol>
 *   <li><b>ID 유효성(확실한 검사)</b>: 모델이 쓴 근거 ID 가 모두 이번 검색 결과에 있어야 하고 하나 이상이어야 한다.
 *   <li><b>보조 휴리스틱(뒷받침 근사 — 검증이 아니다)</b>: 문장별 근거 문자 bigram 겹침, 부정어 불일치(근거 "필요하다" ↔ 답 "필요하지 않다" 류),
 *       숫자 불일치(답에 근거에 없는 숫자).
 * </ol>
 *
 * 통과해도 "근거로 검증됨"이 아니다. 바꿔 말하기·조건 누락·잘못된 결합 같은 충실도 문제는 잡지 못한다 — 실제 모델 평가 전까지 미검증.
 */
public final class CitationVerifier {

  public enum Violation {
    NO_CITATION,
    UNKNOWN_ID,
    UNSUPPORTED_SENTENCE,
    NEGATION_MISMATCH,
    NUMBER_MISMATCH
  }

  public record Result(boolean valid, List<Violation> violations) {}

  private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?。])\\s+|\\n+");
  private static final Pattern DOC_TAG = Pattern.compile("\\[?\\bD\\d+\\b\\]?");
  private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
  private static final Pattern NEGATION =
      Pattern.compile("않|없|아니|불가|금지|못 |못하|말 것|안 ?된|안 ?돼|미적용|제외한다");

  private final double minOverlap;

  /**
   * @param minOverlap 문장 bigram 중 근거에 있는 비율의 하한(기본 0.5)
   */
  public CitationVerifier(double minOverlap) {
    this.minOverlap = minOverlap;
  }

  public Result verify(
      String answer, Collection<String> usedIds, Map<String, String> evidenceById) {
    List<Violation> violations = new ArrayList<>();
    if (usedIds.isEmpty()) {
      violations.add(Violation.NO_CITATION);
      return new Result(false, violations);
    }
    if (!evidenceById.keySet().containsAll(usedIds)) {
      violations.add(Violation.UNKNOWN_ID);
      return new Result(false, violations);
    }
    List<String> evidence = usedIds.stream().map(evidenceById::get).toList();
    String joined = String.join("\n", evidence);
    Set<String> evidenceBigrams = bigrams(joined);
    List<String> evidenceSentences = new ArrayList<>();
    for (String e : evidence) {
      for (String s : SENTENCE_SPLIT.split(e)) {
        if (!s.isBlank()) {
          evidenceSentences.add(s);
        }
      }
    }
    for (String raw : SENTENCE_SPLIT.split(answer)) {
      String s = DOC_TAG.matcher(raw).replaceAll("").strip();
      if (s.replaceAll("\\s", "").length() < 8) {
        continue;
      }
      Set<String> sb = bigrams(s);
      long hit = sb.stream().filter(evidenceBigrams::contains).count();
      if (sb.isEmpty() || (double) hit / sb.size() < minOverlap) {
        add(violations, Violation.UNSUPPORTED_SENTENCE);
      }
      Matcher m = NUMBER.matcher(s);
      while (m.find()) {
        if (!joined.contains(m.group())) {
          add(violations, Violation.NUMBER_MISMATCH);
        }
      }
      String best = bestMatch(sb, evidenceSentences);
      if (best != null && NEGATION.matcher(s).find() != NEGATION.matcher(best).find()) {
        add(violations, Violation.NEGATION_MISMATCH);
      }
    }
    return new Result(violations.isEmpty(), violations);
  }

  private static void add(List<Violation> list, Violation v) {
    if (!list.contains(v)) {
      list.add(v);
    }
  }

  private static String bestMatch(Set<String> sentence, List<String> candidates) {
    String best = null;
    double bestScore = 0;
    for (String c : candidates) {
      Set<String> cb = bigrams(c);
      long hit = sentence.stream().filter(cb::contains).count();
      double score = sentence.isEmpty() ? 0 : (double) hit / sentence.size();
      if (score > bestScore) {
        bestScore = score;
        best = c;
      }
    }
    return best;
  }

  static Set<String> bigrams(String text) {
    String t = text.replaceAll("\\s+", "");
    Set<String> out = new HashSet<>();
    for (int i = 0; i + 2 <= t.length(); i++) {
      out.add(t.substring(i, i + 2));
    }
    return out;
  }
}
