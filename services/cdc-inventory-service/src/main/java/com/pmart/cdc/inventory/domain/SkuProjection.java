package com.pmart.cdc.inventory.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * product-service SKU 의 조회 사본(SkuIssued·ProductVersionPublished 투영). 정본이 아니다. SkuIssued 만 받은 동안은
 * 단위·중량 등이 비어 있다(0 으로 채우지 않는다).
 */
public record SkuProjection(
    String sku,
    @Nullable String productId,
    @Nullable String baseProductId,
    @Nullable String unitType,
    @Nullable Integer unitsPerSku,
    @Nullable Integer weightG,
    @Nullable Integer volumeMl,
    @Nullable String storageType,
    @Nullable Boolean alcohol,
    Logistics logistics,
    SkuState state,
    int productVersion,
    Instant updatedAt) {

  /** 승인된 물류 규격(CDC-F-002). BOX/PLT 환산. 미등록 항목은 null. */
  public record Logistics(
      @Nullable Integer unitsPerBox,
      @Nullable Integer boxesPerPallet,
      @Nullable Integer ti,
      @Nullable Integer hi) {
    public static final Logistics NONE = new Logistics(null, null, null, null);

    public boolean isEmpty() {
      return unitsPerBox == null && boxesPerPallet == null && ti == null && hi == null;
    }
  }

  public enum SkuState {
    ACTIVE,
    INACTIVE
  }

  /** SKU 문자열 접두사(E-/B-)가 단위 유형이다(Commerce 2.7). 형식이 아니면 null. */
  public static @Nullable String unitTypeOf(String sku) {
    if (sku.length() > 2
        && sku.charAt(1) == '-'
        && (sku.charAt(0) == 'E' || sku.charAt(0) == 'B')) {
      return String.valueOf(sku.charAt(0));
    }
    return null;
  }
}
