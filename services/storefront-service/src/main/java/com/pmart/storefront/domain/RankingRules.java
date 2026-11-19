package com.pmart.storefront.domain;

import org.jspecify.annotations.Nullable;

/**
 * 상품 순위 신호 계산(2026-11-19 ADR-0072). 별점순은 베이지안 평균 — (사전 개수 × 사전 평균 + 별점 합) ÷ (사전 개수 + 리뷰 수): 리뷰 3개의
 * 5.0 보다 리뷰 400개의 4.6 이 위. 리뷰가 없으면 점수 없음(정렬 맨 뒤 — 0점이 아니다). 판매는 줄마다 원수량 − 출고 전 취소 − 반품(0 아래로 안 감).
 */
public final class RankingRules {

  private RankingRules() {}

  public static @Nullable Double average(long count, long sum) {
    return count == 0 ? null : Math.round(sum * 10.0 / count) / 10.0;
  }

  public static @Nullable Double score(long count, long sum, int priorCount, double priorMean) {
    if (count == 0) {
      return null;
    }
    double s = (priorCount * priorMean + sum) / (priorCount + count);
    return Math.round(s * 10_000) / 10_000.0;
  }

  public static int netQty(int originalQty, int cancelledBeforeShipQty, int returnedQty) {
    return Math.max(0, originalQty - cancelledBeforeShipQty - returnedQty);
  }
}
