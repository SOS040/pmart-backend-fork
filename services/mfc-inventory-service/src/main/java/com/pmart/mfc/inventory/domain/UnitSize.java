package com.pmart.mfc.inventory.domain;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * 칸 용량 계산에 쓰는 상품 한 단위(SKU — E 낱개 / B 묶음 전체)의 크기(2026-11-16).
 *
 * <p>상품 마스터 치수(ProductVersionPublished.skus[].dimensions, 실측·추정)가 있으면 그것을 쓰고, 없을 때만 더미 등급({@link
 * SkuSize}, 설정 {@code pmart.inventory.dummy-sku-size})으로 대신한다. 둘 다 없으면 호출자가 CONFIG_REQUIRED.
 *
 * @param weightG 한 단위 무게(g). 모르면 null — 그때는 칸 최대 하중을 보지 않는다(무게를 지어내지 않는다)
 * @param label 용량 계산에 쓴 크기 표시(이동 행 size_class): 더미 S·M·L, 상품 치수 MEASURED·ESTIMATED(출처 모르면 PRODUCT)
 * @param checkFit 칸에 어느 방향으로도 들어가지 않으면 용량 0. 상품 치수에만 켠다(더미 계산은 예전 그대로)
 */
public record UnitSize(
    int widthMm,
    int depthMm,
    int heightMm,
    @Nullable Integer weightG,
    String label,
    boolean checkFit) {

  /** 상품 치수 출처를 모를 때의 표시. */
  public static final String PRODUCT = "PRODUCT";

  /** 상품 마스터 치수 → 크기. 세 변이 다 있고 양수일 때만(일부만 있으면 치수 미등록으로 본다 — product-service 가 셋 다 또는 전무로 검증한다). */
  public static @Nullable UnitSize fromProduct(@Nullable SkuProjection p) {
    if (p == null
        || p.widthMm() == null
        || p.depthMm() == null
        || p.heightMm() == null
        || p.widthMm() <= 0
        || p.depthMm() <= 0
        || p.heightMm() <= 0) {
      return null;
    }
    return new UnitSize(
        p.widthMm(),
        p.depthMm(),
        p.heightMm(),
        p.weightG(),
        p.dimensionsSource() != null ? p.dimensionsSource() : PRODUCT,
        true);
  }

  /**
   * 용량 계산 크기: 상품 치수 우선, 없으면 더미 허용 시 더미 등급, 아니면 null(호출자가 CONFIG_REQUIRED).
   *
   * @param dummyAllowed 설정 {@code pmart.inventory.dummy-sku-size}
   */
  public static @Nullable UnitSize resolve(
      String sku, @Nullable SkuProjection p, boolean dummyAllowed) {
    UnitSize real = fromProduct(p);
    if (real != null) {
      return real;
    }
    return dummyAllowed ? SkuSize.dummyFor(sku).unitSize() : null;
  }

  /**
   * 칸에 이 상품이 몇 개 들어가나 = min(칸 부피 × 채움률 ÷ 상품 부피, 최대 하중 ÷ 상품 무게). 칸 치수가 하나라도 없으면 null(계산 불가 → 호출자가
   * CONFIG_REQUIRED). {@code checkFit} 이면 어느 방향으로 놓아도 칸보다 큰 상품은 0.
   */
  public @Nullable Integer capacity(
      @Nullable Integer w, @Nullable Integer d, @Nullable Integer h, @Nullable Integer maxWeightG) {
    if (w == null || d == null || h == null || w <= 0 || d <= 0 || h <= 0) {
      return null;
    }
    if (checkFit && !fitsIn(w, d, h)) {
      return 0;
    }
    long vol = (long) w * d * h;
    long unit = (long) widthMm * depthMm * heightMm;
    int byVolume = (int) Math.floor(vol * SkuSize.FILL_RATE / unit);
    if (maxWeightG != null && maxWeightG > 0 && weightG != null && weightG > 0) {
      return Math.min(byVolume, maxWeightG / weightG);
    }
    return byVolume;
  }

  /** 축에 나란히 놓는 여섯 방향 중 하나라도 칸에 들어가는지 = 작은 변끼리 짝지어 모두 칸 이하. */
  public boolean fitsIn(int w, int d, int h) {
    int[] item = {widthMm, depthMm, heightMm};
    int[] cell = {w, d, h};
    Arrays.sort(item);
    Arrays.sort(cell);
    return item[0] <= cell[0] && item[1] <= cell[1] && item[2] <= cell[2];
  }
}
