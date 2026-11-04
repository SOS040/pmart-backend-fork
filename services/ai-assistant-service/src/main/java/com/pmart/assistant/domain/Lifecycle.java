package com.pmart.assistant.domain;

import java.util.List;

/**
 * 근거의 생애 상태. 지위(Authority)와 별개 — 대체 제안을 받은 확정 정책은 CONFIRMED + SUPERSESSION_PROPOSED 다.
 *
 * <ul>
 *   <li>SUPERSEDED: 승인된 문서가 명시한 절·결정을 대체함 — 현재 정책 질문에서 제외, 이력 질문에서만 포함.
 *   <li>SUPERSESSION_PROPOSED: 승인 안 된 문서가 대체를 선언함 — 원래 지위 유지, 제안 문서를 함께 인용.
 *   <li>EXCLUDED_FROM_PHASE1: 1차 범위에서 제외된 자료 — 현재·이력 질문 모두 제외.
 * </ul>
 */
public enum Lifecycle {
  ACTIVE,
  SUPERSEDED,
  SUPERSESSION_PROPOSED,
  EXCLUDED_FROM_PHASE1;

  /** 검색 WHERE 에서 뺄 생애 상태. 이력 질문은 SUPERSEDED 만 다시 넣는다(EXCLUDED_FROM_PHASE1 은 계속 제외). */
  public static List<Lifecycle> excludedFor(boolean historyQuestion) {
    return historyQuestion
        ? List.of(EXCLUDED_FROM_PHASE1)
        : List.of(SUPERSEDED, EXCLUDED_FROM_PHASE1);
  }
}
