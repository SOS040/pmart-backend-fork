package com.pmart.mfc.inventory.domain;

import org.jspecify.annotations.Nullable;

/**
 * 상품 크기 — 실제 정보가 없어 시스템 구현용 **더미**(2026-11-08 사용자 요청: 큰 것·중간·작은 것에 적당한 값). SKU 코드로 늘 같은 등급을 고른다. 운영
 * 값 아님. 2026-11-16 부터 상품 마스터 치수(SKU 투영 widthMm·depthMm·heightMm)가 있으면 그것이 우선이고, 더미는 치수 미등록 SKU 의
 * 대체로만 쓴다({@link UnitSize#resolve}).
 */
public enum SkuSize {
  /** 작은 것 — 껌·소스 등. 80×60×40 mm, 150 g. */
  S(80, 60, 40, 150),
  /** 중간 — 우유 1L·과자 봉지. 150×100×100 mm, 600 g. */
  M(150, 100, 100, 600),
  /** 큰 것 — 생수 묶음·쌀. 300×200×200 mm, 2,500 g. */
  L(300, 200, 200, 2_500);

  /** 칸을 꽉 채우지 못하는 빈틈(포장 모양·손 넣을 공간). */
  public static final double FILL_RATE = 0.8;

  public final int widthMm;
  public final int depthMm;
  public final int heightMm;
  public final int weightG;

  SkuSize(int widthMm, int depthMm, int heightMm, int weightG) {
    this.widthMm = widthMm;
    this.depthMm = depthMm;
    this.heightMm = heightMm;
    this.weightG = weightG;
  }

  /** 더미 등급: SKU 코드 해시로 고정(S 50%·M 35%·L 15% 쯤). */
  public static SkuSize dummyFor(String sku) {
    int h = Math.floorMod(sku.hashCode(), 100);
    return h < 50 ? S : h < 85 ? M : L;
  }

  /** 이 더미 등급의 용량 계산 크기(맞음 검사 없음 — 2026-11-16 이전 계산 그대로). */
  public UnitSize unitSize() {
    return new UnitSize(widthMm, depthMm, heightMm, weightG, name(), false);
  }

  /**
   * 칸에 이 상품이 몇 개 들어가나 = min(부피 × 채움률 ÷ 상품 부피, 최대 하중 ÷ 상품 무게). 치수가 하나라도 없으면 null(계산 불가 → 호출자가
   * CONFIG_REQUIRED).
   */
  public @Nullable Integer capacity(
      @Nullable Integer w, @Nullable Integer d, @Nullable Integer h, @Nullable Integer maxWeightG) {
    return unitSize().capacity(w, d, h, maxWeightG);
  }
}
