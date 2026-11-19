package com.pmart.product.domain;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * SKU 내용량(2026-11-19, 클라이언트 BR-19). 판매 단위(E 낱개 / B 묶음 전체)에 든 상품의 양 — g 또는 mL 정수. 포장 무게(weightG,
 * 물류)와 다르다. 고객 화면 100g·100mL당 가격의 분모다.
 */
public record NetContent(int amount, String unit) {

  public static final int MIN = 1;
  public static final int MAX = 1_000_000;
  public static final Set<String> UNITS = Set.of("G", "ML");

  /** 초안 입력 그대로(일부만 있을 수 있음). 검증은 {@link DraftRules}. */
  public record Input(@Nullable Integer amount, @Nullable String unit) {

    public static final Input NONE = new Input(null, null);

    public boolean empty() {
      return amount == null && unit == null;
    }

    /** 둘 다 있으면 내용량, 아니면 null. 범위 검사는 하지 않는다(검증된 초안에만 쓴다). */
    public @Nullable NetContent complete() {
      return amount == null || unit == null ? null : new NetContent(amount, unit);
    }
  }
}
