package com.pmart.storefront.domain;

import org.jspecify.annotations.Nullable;

/**
 * 진열 카드 단위 가격(2026-11-19, 클라이언트 BR-19 — "100g당 999원"). 가격 × 100 ÷ 내용량, 원 단위 반올림(0.5 올림).
 * product-content 상세의 단위 가격과 같은 식이다.
 */
public final class UnitPrice {

  public static final int BASIS = 100;

  private UnitPrice() {}

  /** 가격·내용량이 둘 다 있고 내용량이 양수일 때만, 아니면 null. */
  public static @Nullable Long of(@Nullable Long price, @Nullable Integer amount) {
    if (price == null || amount == null || amount <= 0 || price < 0) {
      return null;
    }
    return (price * BASIS * 2 + amount) / (2L * amount);
  }
}
