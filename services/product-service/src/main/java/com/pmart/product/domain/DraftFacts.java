package com.pmart.product.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 검증에 필요한 초안 값(계약 DTO 와 분리). 1차는 상품 1개에 SKU 1개.
 *
 * @param categoryPaths 이름 경로("국·탕·찌개 > 국·국밥"). categoryIds 가 비었을 때 자동 매핑에 쓴다
 */
public record DraftFacts(
    @Nullable String internalName,
    @Nullable String baseProductId,
    @Nullable String unitType,
    @Nullable Integer unitsPerSku,
    @Nullable Integer weightG,
    @Nullable String storageType,
    @Nullable Integer shelfMinRemainingDays,
    @Nullable Integer shelfMaxDaysSinceManufacture,
    SkuDimensions.Input dimensions,
    List<String> barcodes,
    @Nullable String primaryCategoryId,
    List<String> categoryIds,
    List<String> categoryPaths,
    List<FilterOptionRef> filterOptions) {

  public record FilterOptionRef(String filterGroupId, String option) {}
}
