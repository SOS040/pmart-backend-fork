package com.pmart.customerassistant.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 기준선 발췌 답변(생성 없음). 상위 근거를 <b>온전한 청크 단위</b>로 보여 준다 — 조건·예외·표 헤더가 함께 남는다.
 *
 * <p>길이 제한은 청크 개수를 줄여서 맞춘다(청크를 자르지 않는다, 3차 검토 4). 첫 청크는 한도를 넘어도 통째로 넣는다. 근거마다 지위·생애·구현 기록 기준일 꼬리표를
 * 붙이고, 서로 다른 근거를 하나로 합치지 않고 나란히 둔다(정렬 = 유사도 + 지위 + 문서 우선순위).
 */
public final class ExtractiveComposer {

  /** 고객용 발췌 머리말 — 안내 문서 원문을 그대로 보여 준다. */
  public static final String BASELINE_NOTE = "관련 안내를 그대로 보여 드릴게요.";

  /** 절 제목·본문의 지위 표시 태그([확정 정책]·[채택 설계]·[설계안]·[결정 필요] 등) — 고객에게 보이지 않는다. */
  private static final java.util.regex.Pattern TAG =
      java.util.regex.Pattern.compile("[ \\t]*\\[(?:확정 정책|채택 설계|설계안|결정 필요|가정|제안|외부 참고|팀 확인 필요)]");

  /** 고객에게 보일 본문: 지위 태그를 뺀다. */
  public static String stripTags(String text) {
    return TAG.matcher(text).replaceAll("");
  }

  public record Composition(String text, List<RetrievedChunk> used) {}

  private ExtractiveComposer() {}

  /**
   * @param ranked 정렬된 근거(임계값 통과분)
   * @param companions 대체 제안 문서의 동반 근거(SUPERSESSION_PROPOSED 일 때) — 본 근거 뒤에 붙는다
   */
  public static Composition compose(
      List<RetrievedChunk> ranked, List<RetrievedChunk> companions, int maxChars, int maxChunks) {
    List<RetrievedChunk> used = new ArrayList<>();
    int total = 0;
    for (RetrievedChunk c : ranked) {
      if (used.size() >= maxChunks) {
        break;
      }
      int len = c.content().length();
      if (!used.isEmpty() && total + len > maxChars) {
        continue; // 자르지 않는다 — 더 짧은 다음 청크가 들어갈 수 있으면 넣는다
      }
      used.add(c);
      total += len;
    }
    List<RetrievedChunk> withCompanions = new ArrayList<>(used);
    for (RetrievedChunk comp : companions) {
      boolean needed =
          used.stream().anyMatch(u -> u.proposedSupersededBy().contains(comp.documentId()));
      boolean already = withCompanions.stream().anyMatch(u -> u.chunkId().equals(comp.chunkId()));
      if (needed && !already) {
        withCompanions.add(comp);
      }
    }
    return new Composition(render(withCompanions, used), List.copyOf(withCompanions));
  }

  private static String render(List<RetrievedChunk> all, List<RetrievedChunk> primary) {
    // 고객용: 문서 경로·지위 꼬리표 없이 안내 본문만(태그 제거). 근거끼리 합치지 않고 나란히 둔다
    StringBuilder sb = new StringBuilder(BASELINE_NOTE).append('\n');
    for (RetrievedChunk c : all) {
      sb.append('\n').append(stripTags(c.content()).strip()).append('\n');
    }
    return sb.toString().stripTrailing();
  }
}
