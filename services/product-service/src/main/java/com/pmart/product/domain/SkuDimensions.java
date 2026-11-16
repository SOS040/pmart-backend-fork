package com.pmart.product.domain;

import org.jspecify.annotations.Nullable;

/**
 * SKU 실물 치수(2026-11-16, mm). 판매 단위(E 낱개 / B 묶음 전체) 하나의 외곽 세 변 — 놓는 방향과 무관하다. MFC 칸 용량 계산에 쓴다.
 *
 * @param source MEASURED(실측) / ESTIMATED(추정). 모르면 null
 */
public record SkuDimensions(int widthMm, int depthMm, int heightMm, @Nullable String source) {

  public static final int MIN_MM = 1;
  public static final int MAX_MM = 5000;
  public static final java.util.Set<String> SOURCES = java.util.Set.of("MEASURED", "ESTIMATED");

  /** 초안 입력 그대로(일부만 있을 수 있음). 검증은 {@link DraftRules}. */
  public record Input(
      @Nullable Integer widthMm,
      @Nullable Integer depthMm,
      @Nullable Integer heightMm,
      @Nullable String source) {

    /** 아무것도 주지 않았다(치수 미등록). */
    public boolean empty() {
      return widthMm == null && depthMm == null && heightMm == null && source == null;
    }

    /** 세 변이 다 있으면 치수, 아니면 null. 범위·출처 검사는 하지 않는다(검증된 초안에만 쓴다). */
    public @Nullable SkuDimensions complete() {
      if (widthMm == null || depthMm == null || heightMm == null) {
        return null;
      }
      return new SkuDimensions(widthMm, depthMm, heightMm, source);
    }
  }
}
