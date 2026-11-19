package com.pmart.storefront.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.storefront.*}.
 *
 * @param contentBaseUrl product-content-service 주소 — 콘텐츠 사건에 없는 상품명·대표 이미지를 공개 카드 API 로 보강
 * @param ranking 순위 신호(ADR-0072) — 비면 신호를 내지 않는다(정책값을 기본값으로 대신하지 않음)
 */
@ConfigurationProperties(prefix = "pmart.storefront")
public record StorefrontProperties(
    String contentBaseUrl, @org.jspecify.annotations.Nullable Ranking ranking) {

  /**
   * @param salesWindowDays 인기순 판매 집계 기간(일) — 사용자 추천 채택 30
   * @param ratingPriorCount 별점순 베이지안 사전 개수(리뷰가 적을수록 사전 평균 쪽으로)
   * @param ratingPriorMean 별점순 베이지안 사전 평균
   */
  public record Ranking(
      @org.jspecify.annotations.Nullable Integer salesWindowDays,
      @org.jspecify.annotations.Nullable Integer ratingPriorCount,
      @org.jspecify.annotations.Nullable Double ratingPriorMean) {
    public boolean complete() {
      return salesWindowDays != null
          && salesWindowDays > 0
          && ratingPriorCount != null
          && ratingPriorCount >= 0
          && ratingPriorMean != null
          && ratingPriorMean >= 1
          && ratingPriorMean <= 5;
    }
  }

  public StorefrontProperties {
    contentBaseUrl =
        contentBaseUrl == null || contentBaseUrl.isBlank()
            ? "http://product-content-service:8102"
            : contentBaseUrl;
  }
}
