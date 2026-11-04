package com.pmart.assistant.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 기준선 발췌 답변(생성 없음). 상위 근거를 <b>온전한 청크 단위</b>로 보여 준다 — 조건·예외·표 헤더가 함께 남는다.
 *
 * <p>길이 제한은 청크 개수를 줄여서 맞춘다(청크를 자르지 않는다, 3차 검토 4). 첫 청크는 한도를 넘어도 통째로 넣는다. 근거마다 지위·생애·구현 기록 기준일 꼬리표를
 * 붙이고, 서로 다른 근거를 하나로 합치지 않고 나란히 둔다(정렬 = 유사도 + 지위 + 문서 우선순위).
 */
public final class ExtractiveComposer {

  public static final String NO_SETTLED_PREFIX = "확정 정책 근거 없음 — 아래는 설계안·미결정 기준입니다.";
  public static final String BASELINE_NOTE =
      "(기준선 발췌 답변: 근거 문서 원문을 그대로 보여 줍니다. 생성 답변이 아니며, 근거가 서로 다르면 합치지 않고 나란히 둡니다.)";

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
    StringBuilder sb = new StringBuilder();
    if (primary.stream().noneMatch(c -> c.authority().isSettled())) {
      sb.append(NO_SETTLED_PREFIX).append('\n');
    }
    sb.append(BASELINE_NOTE).append('\n');
    int n = 1;
    for (RetrievedChunk c : all) {
      boolean companion = !primary.contains(c);
      sb.append('\n')
          .append('[')
          .append(n++)
          .append("] ")
          .append(companion ? "[대체 제안 문서(미승인)]" : "")
          .append(c.labels())
          .append(' ')
          .append(c.path());
      if (c.section() != null) {
        sb.append(" — ").append(c.section());
      }
      sb.append('\n').append(c.content()).append('\n');
    }
    return sb.toString().stripTrailing();
  }
}
