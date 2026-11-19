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
    String contentBaseUrl,
    @org.jspecify.annotations.Nullable Ranking ranking,
    @org.jspecify.annotations.Nullable Tabs tabs) {

  /**
   * 랭킹·신상품 탭(2026-11-20 ADR-0075, 사용자 "탭 + 배너·테마"). Commerce "신규 판정기간·순위 집계기간·평가 기준은 승인 설정" — 코드
   * 기본값 없음. 비면 그 탭을 내지 않는다(tabs 목록에서 빠지고 요청은 404). 순위 집계기간은 {@link Ranking#salesWindowDays}.
   *
   * @param newWindowDays 신상품으로 볼 기간(처음 게시 뒤 일 수)
   * @param rankingSize 랭킹 탭에 보이는 상품 수(1위부터)
   */
  public record Tabs(
      @org.jspecify.annotations.Nullable Integer newWindowDays,
      @org.jspecify.annotations.Nullable Integer rankingSize) {
    public boolean newTab() {
      return newWindowDays != null && newWindowDays > 0;
    }

    public boolean rankingTab() {
      return rankingSize != null && rankingSize > 0;
    }
  }

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
