package com.pmart.customerassistant.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 검색된 청크와 그 메타데이터. 인용은 이 값으로만 만든다(모델이 경로·버전을 지어내지 않는다).
 *
 * @param similarity 혼합 유사도(코사인 + 키워드). 임계값 판정은 이 값으로만 한다.
 * @param finalScore 임계값을 넘은 뒤의 정렬 점수(유사도 + 지위 가중 + 우선순위 가중)
 */
public record RetrievedChunk(
    String chunkId,
    String documentId,
    String path,
    @Nullable String section,
    Authority authority,
    Lifecycle lifecycle,
    @Nullable String supersededBy,
    List<String> proposedSupersededBy,
    int precedenceRank,
    @Nullable String implRecordDate,
    @Nullable String implNote,
    @Nullable String documentVersion,
    @Nullable String effectiveDate,
    String accessScope,
    String content,
    double similarity,
    double finalScore) {

  public RetrievedChunk withFinalScore(double score) {
    return new RetrievedChunk(
        chunkId,
        documentId,
        path,
        section,
        authority,
        lifecycle,
        supersededBy,
        proposedSupersededBy,
        precedenceRank,
        implRecordDate,
        implNote,
        documentVersion,
        effectiveDate,
        accessScope,
        content,
        similarity,
        score);
  }

  /** 답변 꼬리표: [확정]·[설계안]·[대체됨]·[대체 제안(미승인)]… */
  public String labels() {
    StringBuilder sb = new StringBuilder("[").append(authority.label()).append(']');
    if (lifecycle == Lifecycle.SUPERSEDED) {
      sb.append("[대체됨").append(supersededBy == null ? "" : " → " + supersededBy).append(']');
    } else if (lifecycle == Lifecycle.SUPERSESSION_PROPOSED) {
      sb.append("[대체 제안 있음(미승인): ").append(String.join(", ", proposedSupersededBy)).append(']');
    }
    if (implRecordDate != null) {
      sb.append('[').append(implRecordDate).append(" 기준 구현 기록 — 현재 상태는 이 기록만으로 확인할 수 없음]");
    }
    return sb.toString();
  }
}
