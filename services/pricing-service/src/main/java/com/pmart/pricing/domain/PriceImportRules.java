package com.pmart.pricing.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 가격 일괄 등록 행 분류(사용자 결정 2026-11-03). 묶음 basePrice 를 미리 채우고 운영자가 행마다 고치거나 뺀다. 제출 대상은 가격이 없던 SKU(NEW)와
 * 현재 가격과 다른 SKU(CHANGE)이고, 현재 가격과 같으면(UNCHANGED) 뺀다. 0원 이하는 받지 않는다(BO-04 0원 미정).
 */
public final class PriceImportRules {

  private PriceImportRules() {}

  public enum Status {
    READY,
    EXCLUDED,
    UNCHANGED,
    SKU_NOT_FOUND,
    PRICE_REQUIRED,
    DUPLICATE_SKU
  }

  public enum ChangeType {
    NEW,
    CHANGE,
    UNCHANGED
  }

  public record Classified(Status status, @Nullable ChangeType changeType) {
    public boolean submittable() {
      return status == Status.READY;
    }
  }

  /** 분류에 필요한 행 값. */
  public record LineFacts(
      @Nullable String sku,
      @Nullable Long basePrice,
      @Nullable Long currentPrice,
      boolean excluded) {}

  public static Classified classify(LineFacts f, boolean duplicateSku) {
    if (f.sku() == null) {
      return new Classified(Status.SKU_NOT_FOUND, null);
    }
    if (duplicateSku) {
      return new Classified(Status.DUPLICATE_SKU, null);
    }
    if (f.basePrice() == null || f.basePrice() <= 0) {
      return new Classified(Status.PRICE_REQUIRED, null);
    }
    ChangeType type =
        f.currentPrice() == null
            ? ChangeType.NEW
            : f.currentPrice().longValue() == f.basePrice().longValue()
                ? ChangeType.UNCHANGED
                : ChangeType.CHANGE;
    if (type == ChangeType.UNCHANGED) {
      return new Classified(Status.UNCHANGED, type);
    }
    return new Classified(f.excluded() ? Status.EXCLUDED : Status.READY, type);
  }

  /** 묶음 순서대로 분류한다. 같은 SKU 가 다시 나오면 뒤 행은 DUPLICATE_SKU(앞 행만 쓴다). */
  public static List<Classified> classifyAll(List<LineFacts> lines) {
    Set<String> seen = new HashSet<>();
    return lines.stream().map(f -> classify(f, f.sku() != null && !seen.add(f.sku()))).toList();
  }
}
