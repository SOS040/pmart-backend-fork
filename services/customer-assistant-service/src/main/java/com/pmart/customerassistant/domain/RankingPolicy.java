package com.pmart.customerassistant.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 순위 규칙(2차 검토 5). 임계값을 넘은 후보끼리만 {@code 유사도 + 지위 가중 + 우선순위 등급 가중} 으로 정렬한다. 임계값 미만은 지위로 끌어올리지 않는다(먼저
 * 걸러낸다).
 *
 * @param threshold 혼합 유사도 하한
 * @param authorityWeights 지위별 가중(확정 > 채택 설계 > 설계안 > 가정 > 미결정·외부 참고)
 * @param precedenceWeight 우선순위 등급(domain-map.md:35) 1단계당 가중
 */
public record RankingPolicy(
    double threshold, Map<Authority, Double> authorityWeights, double precedenceWeight) {

  public static Map<Authority, Double> defaultWeights() {
    return Map.of(
        Authority.CONFIRMED, 0.06,
        Authority.ADOPTED_DESIGN, 0.04,
        Authority.PROPOSAL, 0.02,
        Authority.ASSUMPTION, 0.01,
        Authority.UNDECIDED, 0.0,
        Authority.EXTERNAL_REFERENCE, 0.0);
  }

  public List<RetrievedChunk> rank(List<RetrievedChunk> candidates, int topK) {
    return candidates.stream()
        .filter(c -> c.similarity() >= threshold)
        .map(
            c ->
                c.withFinalScore(
                    c.similarity()
                        + authorityWeights.getOrDefault(c.authority(), 0.0)
                        + precedenceWeight * c.precedenceRank()))
        .sorted(Comparator.comparingDouble(RetrievedChunk::finalScore).reversed())
        .limit(topK)
        .toList();
  }
}
