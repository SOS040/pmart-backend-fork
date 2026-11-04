package com.pmart.assistant.infrastructure.ingest;

import com.pmart.assistant.domain.Authority;
import com.pmart.assistant.domain.Lifecycle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * ADR "- 대체:" 선언 → 대체 관계(검토 1).
 *
 * <p>관계는 <b>대체하는 ADR 이 승인(CONFIRMED)이고 대상 결정 번호가 명시된 경우에만 활성</b>이다 — 그 결정 청크만 SUPERSEDED. 그 밖(제안·초안
 * ADR, 결정 번호 없음)은 대상 쪽을 SUPERSESSION_PROPOSED 로 표시만 하고 원래 지위를 유지한다. 대상마다 <b>첫 언급</b>의 결정 번호만 쓴다(같은
 * 줄 뒤쪽의 "결정 1·2 는 유지한다"를 대체로 읽지 않기 위해). ADR 이 아닌 대상(예: tech-stack.md 의 문장)은 결정 단위가 없어 자동 판정하지 않는다 —
 * 필요하면 manifest overrides.
 */
public final class SupersessionResolver {

  /** 대체 관계 하나. decisions 가 비면 대상이 모호함(문서 단위 제안 표시). */
  public record Relation(
      String supersedingPath, String targetPath, Set<Integer> decisions, boolean active) {}

  private static final Pattern MENTION = Pattern.compile("ADR-(\\d{4})");
  private static final Pattern DECISIONS =
      Pattern.compile(
          "^\\s*(?:의\\s*)?결정\\s*((?:\\d+(?:\\s*~\\s*\\d+)?(?:\\([^)]*\\))?\\s*[·,]?\\s*)+)");
  private static final Pattern NUMBER =
      Pattern.compile("(\\d+)(?:\\s*~\\s*(\\d+))?(?:\\([^)]*\\))?");

  private SupersessionResolver() {}

  /**
   * @param supersedingPath 대체하는 ADR 경로
   * @param authority 그 ADR 의 지위(상태 줄)
   * @param lines "- 대체:" 줄들
   * @param adrPathByNumber ADR 번호("0019") → manifest 경로
   */
  public static List<Relation> parse(
      String supersedingPath,
      Authority authority,
      List<String> lines,
      Map<String, String> adrPathByNumber) {
    Map<String, Set<Integer>> firstMention = new LinkedHashMap<>();
    for (String line : lines) {
      Matcher m = MENTION.matcher(line);
      while (m.find()) {
        String number = m.group(1);
        String target = adrPathByNumber.get(number);
        if (target == null || target.equals(supersedingPath) || firstMention.containsKey(target)) {
          continue;
        }
        firstMention.put(target, decisionsAfter(line.substring(m.end())));
      }
    }
    List<Relation> out = new ArrayList<>();
    firstMention.forEach(
        (target, decisions) ->
            out.add(
                new Relation(
                    supersedingPath,
                    target,
                    decisions,
                    authority == Authority.CONFIRMED && !decisions.isEmpty())));
    return out;
  }

  static Set<Integer> decisionsAfter(String rest) {
    Matcher d = DECISIONS.matcher(rest);
    Set<Integer> out = new LinkedHashSet<>();
    if (!d.find()) {
      return out;
    }
    Matcher n = NUMBER.matcher(d.group(1));
    while (n.find()) {
      int from = Integer.parseInt(n.group(1));
      int to = n.group(2) == null ? from : Integer.parseInt(n.group(2));
      for (int i = from; i <= to; i++) {
        out.add(i);
      }
    }
    return out;
  }

  /** 청크 하나에 적용된 결과. */
  public record Applied(
      Lifecycle lifecycle, @Nullable String supersededBy, List<String> proposedSupersededBy) {}

  /**
   * 대상 문서 청크에 관계를 적용한다. 활성 관계는 명시된 결정 청크만 SUPERSEDED. 비활성 관계는 명시된 결정 청크(번호 없으면 문서 전체)를
   * SUPERSESSION_PROPOSED 로 표시하고 지위는 바꾸지 않는다. 이미 1차 제외면 그대로 둔다.
   */
  public static Applied apply(
      Lifecycle current, @Nullable Integer decisionNo, List<Relation> relationsOnTarget) {
    if (current == Lifecycle.EXCLUDED_FROM_PHASE1) {
      return new Applied(current, null, List.of());
    }
    String supersededBy = null;
    List<String> proposed = new ArrayList<>();
    for (Relation r : relationsOnTarget) {
      boolean hits =
          r.decisions().isEmpty() || (decisionNo != null && r.decisions().contains(decisionNo));
      if (!hits) {
        continue;
      }
      if (r.active()) {
        supersededBy = r.supersedingPath();
      } else if (!proposed.contains(r.supersedingPath())) {
        proposed.add(r.supersedingPath());
      }
    }
    if (supersededBy != null) {
      return new Applied(Lifecycle.SUPERSEDED, supersededBy, List.of());
    }
    if (!proposed.isEmpty()) {
      return new Applied(Lifecycle.SUPERSESSION_PROPOSED, null, List.copyOf(proposed));
    }
    return new Applied(current, null, List.of());
  }
}
