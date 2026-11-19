package com.pmart.search.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * 동의어 규칙 바꾸기(ADR-0073 단계 B). 승인된 변경 묶음을 지금 규칙에 적용한다 — 기준 버전이 지금 버전과 같고 모든 행이 맞을 때만 전부 적용, 하나라도 어긋나면
 * 하나도 적용하지 않는다(버전 하나로 묶인 변경이라 일부만 넣으면 요청자가 본 결과와 달라진다).
 *
 * <p>낱말 형식 기준은 approval-service {@code SearchSynonymTargets} 와 같다(요청 때 한 번, 반영 때 다시 — 사건이 정본으로 오는
 * 길이라).
 */
public final class SynonymRules {

  public static final int MAX_TERMS = 20;
  public static final int MAX_TERM_LENGTH = 30;

  /** 규칙 하나: id 와 같은 뜻 낱말(소문자·앞뒤 공백 없음). */
  public record Rule(String id, List<String> terms) {
    public Rule {
      terms = List.copyOf(terms);
    }
  }

  /** 바꿀 행: op UPSERT(terms 필요)·DELETE. */
  public record Change(String ruleId, @Nullable String op, @Nullable List<String> terms) {}

  /**
   * 적용 결과.
   *
   * @param reason 적용하지 않은 까닭(STALE_VERSION·LINE_REJECTED), 적용했으면 null
   * @param lineReasons 행(규칙 ID) → 그 행이 어긋난
   *     까닭(RULE_NOT_FOUND·INVALID_TERMS·INVALID_OP·DUPLICATE_RULE)
   * @param rules 적용했으면 새 규칙 전체(id 순), 아니면 지금 규칙 그대로
   */
  public record Outcome(
      @Nullable String reason, Map<String, String> lineReasons, List<Rule> rules) {
    public boolean applied() {
      return reason == null;
    }
  }

  private SynonymRules() {}

  public static Outcome apply(
      List<Rule> current,
      long currentVersion,
      @Nullable Long expectedVersion,
      List<Change> changes) {
    if (expectedVersion == null || expectedVersion != currentVersion) {
      return new Outcome("STALE_VERSION", Map.of(), current);
    }
    Map<String, Rule> next = new TreeMap<>();
    for (Rule r : current) {
      next.put(r.id(), r);
    }
    Map<String, String> bad = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    for (Change c : changes) {
      if (!seen.add(c.ruleId())) {
        bad.put(c.ruleId(), "DUPLICATE_RULE");
        continue;
      }
      if ("DELETE".equals(c.op())) {
        if (next.remove(c.ruleId()) == null) {
          bad.put(c.ruleId(), "RULE_NOT_FOUND");
        }
      } else if ("UPSERT".equals(c.op())) {
        List<String> terms = normalize(c.terms());
        if (terms == null) {
          bad.put(c.ruleId(), "INVALID_TERMS");
        } else {
          next.put(c.ruleId(), new Rule(c.ruleId(), terms));
        }
      } else {
        bad.put(c.ruleId(), "INVALID_OP");
      }
    }
    if (changes.isEmpty() || !bad.isEmpty()) {
      return new Outcome("LINE_REJECTED", bad, current);
    }
    return new Outcome(null, Map.of(), List.copyOf(next.values()));
  }

  /** 낱말을 다듬는다(앞뒤 공백, 소문자 — 검색 분석기가 소문자로 바꾼 뒤 동의어를 본다). 형식이 어긋나면 null. */
  public static @Nullable List<String> normalize(@Nullable List<String> terms) {
    if (terms == null || terms.size() < 2 || terms.size() > MAX_TERMS) {
      return null;
    }
    List<String> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String t : terms) {
      String s = t == null ? "" : t.strip().toLowerCase(Locale.ROOT);
      if (s.isEmpty()
          || s.length() > MAX_TERM_LENGTH
          || s.contains(",")
          || s.contains("=>")
          || s.contains("#")
          || !seen.add(s)) {
        return null;
      }
      out.add(s);
    }
    return out;
  }

  /** ES 동의어 집합 규칙(id·synonyms "a, b, c"). */
  public static List<Map<String, String>> esRules(List<Rule> rules) {
    List<Map<String, String>> out = new ArrayList<>();
    for (Rule r : rules) {
      out.add(Map.of("id", r.id(), "synonyms", String.join(", ", r.terms())));
    }
    return out;
  }
}
