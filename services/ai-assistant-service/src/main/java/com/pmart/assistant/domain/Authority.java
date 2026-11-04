package com.pmart.assistant.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 근거의 정책 지위. 생애(Lifecycle)·구현 기록(implementationEvidence)과는 다른 축이다.
 *
 * <p>문서 기본값을 절·문단 시작 태그가 덮는다. 태그 → 지위 대응은 업무 문서 표기 규칙(예: Commerce 표기 규칙·2.5, OMS 표기 규칙)을 따른다. 판단이 안
 * 되는 태그는 지위를 바꾸지 않는다(문서 기본값 유지).
 */
public enum Authority {
  CONFIRMED("확정"),
  ADOPTED_DESIGN("채택 설계"),
  PROPOSAL("설계안"),
  ASSUMPTION("가정"),
  UNDECIDED("미결정"),
  EXTERNAL_REFERENCE("외부 참고");

  private final String label;

  Authority(String label) {
    this.label = label;
  }

  /** 답변 표시용 꼬리표. */
  public String label() {
    return label;
  }

  /** 확정·채택 근거인가 — 아니면 "확정 정책 근거 없음" 머리말 대상. */
  public boolean isSettled() {
    return this == CONFIRMED || this == ADOPTED_DESIGN;
  }

  /** 문단·제목 맨 앞(또는 제목 끝)의 `[태그]`. 백틱으로 감싼 경우도 받는다. */
  private static final Pattern TAG = Pattern.compile("^`?\\*{0,2}\\[([^\\]]{1,40})\\]");

  /** 문단 첫머리 태그로 지위를 판정한다. 목록 기호·번호·굵게 표시는 건너뛴다. */
  public static Optional<Authority> fromLeadingTag(String paragraph) {
    String s = paragraph.stripLeading().replaceFirst("^(?:[-*+]\\s+|\\d+\\.\\s+)", "");
    Matcher m = TAG.matcher(s);
    return m.find() ? fromTag(m.group(1)) : Optional.empty();
  }

  /** 제목 안의 태그(예: "## 부록 D … [보완 설계]"). */
  public static Optional<Authority> fromHeadingTag(String heading) {
    Matcher m = Pattern.compile("\\[([^\\]]{1,40})\\]").matcher(heading);
    while (m.find()) {
      Optional<Authority> a = fromTag(m.group(1));
      if (a.isPresent()) {
        return a;
      }
    }
    return Optional.empty();
  }

  /** 태그 본문 → 지위. 복합 태그("확정 / 수치는 가정")는 앞쪽 지위를 따른다. */
  public static Optional<Authority> fromTag(String rawTag) {
    String t = rawTag.strip();
    if (t.startsWith("확정")
        || t.startsWith("사용자 확정")
        || t.startsWith("사용자 최종 확정")
        || t.startsWith("사용자 재확인")) {
      return Optional.of(CONFIRMED);
    }
    if (t.startsWith("채택 설계") || t.startsWith("보완 설계") || t.startsWith("명세 설계")) {
      return Optional.of(ADOPTED_DESIGN);
    }
    if (t.startsWith("설계안") || t.startsWith("제안")) {
      return Optional.of(PROPOSAL);
    }
    if (t.startsWith("가정")) {
      return Optional.of(ASSUMPTION);
    }
    if (t.startsWith("결정 필요")
        || t.startsWith("팀 확인 필요")
        || t.startsWith("미확정")
        || t.startsWith("예시·미확정")) {
      return Optional.of(UNDECIDED);
    }
    if (t.startsWith("외부 참고") || t.startsWith("외부 확인")) {
      return Optional.of(EXTERNAL_REFERENCE);
    }
    return Optional.empty();
  }

  /** ADR 상태 줄("- 상태: 승인 (…)")의 지위. "승인"으로 시작할 때만 CONFIRMED, 그 밖(제안·초안·일부 대체됨)은 PROPOSAL. */
  public static Authority fromAdrStatus(String statusLine) {
    String s = statusLine.replaceFirst("^\\s*-\\s*상태\\s*:\\s*", "").strip();
    return s.startsWith("승인") ? CONFIRMED : PROPOSAL;
  }
}
