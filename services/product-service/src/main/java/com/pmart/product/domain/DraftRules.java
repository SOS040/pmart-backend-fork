package com.pmart.product.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 상품 등록 초안 검증(BO-03). 비어 있는 단위·중량·보관 온도를 0·기본값으로 채우지 않고 문제로 남긴다. 바코드 하나가 다른 SKU 를 이미 가리키면 자동으로 고르지
 * 않고 충돌로 둔다.
 */
public final class DraftRules {

  /** 검증 결과. {@code categoryIds} 는 대표 카테고리가 첫 번째. */
  public record Result(List<DraftProblem> problems, List<String> categoryIds) {
    public boolean valid() {
      return problems.isEmpty();
    }
  }

  private DraftRules() {}

  /**
   * @param ownSkus 이 초안이 갱신하는 기존 상품의 SKU(재적재). 자기 바코드는 충돌이 아니다
   */
  public static Result validate(DraftFacts f, CatalogLookup lookup, Set<String> ownSkus) {
    List<DraftProblem> p = new ArrayList<>();
    if (blank(f.internalName())) {
      p.add(DraftProblem.of("NAME_REQUIRED", "product.internalName", "관리용 상품명이 없습니다."));
    }
    if (f.baseProductId() != null && !lookup.baseProductExists(f.baseProductId())) {
      p.add(
          DraftProblem.of(
              "BASE_PRODUCT_NOT_FOUND",
              "product.baseProductId",
              "기초상품이 없습니다: " + f.baseProductId()));
    }
    if (!"E".equals(f.unitType()) && !"B".equals(f.unitType())) {
      p.add(DraftProblem.of("UNIT_TYPE_REQUIRED", "skus[0].unitType", "E(단품) 또는 B(묶음)여야 합니다."));
    }
    if (f.unitsPerSku() == null || f.unitsPerSku() < 1) {
      p.add(DraftProblem.of("UNITS_PER_SKU_REQUIRED", "skus[0].unitsPerSku", "구성 낱개 수는 1 이상입니다."));
    } else if ("E".equals(f.unitType()) && f.unitsPerSku() != 1) {
      p.add(
          DraftProblem.of(
              "UNITS_PER_SKU_INVALID", "skus[0].unitsPerSku", "단품(E) SKU 의 구성 낱개 수는 1 입니다."));
    }
    if (f.weightG() == null) {
      p.add(DraftProblem.of("WEIGHT_REQUIRED", "skus[0].weightG", "중량(g)이 없습니다. 0 으로 채우지 않습니다."));
    }
    if (f.storageType() == null) {
      p.add(
          DraftProblem.of(
              "STORAGE_TYPE_REQUIRED", "skus[0].storageType", "보관 온도(상온/냉장/냉동)가 없습니다."));
    }
    checkShelfLife(f.shelfMinRemainingDays(), "minRemainingDays", p);
    checkShelfLife(f.shelfMaxDaysSinceManufacture(), "maxDaysSinceManufacture", p);
    checkDimensions(f.dimensions(), p);
    checkBarcodes(f.barcodes(), lookup, ownSkus, p);
    List<String> categories = resolveCategories(f, lookup, p);
    for (DraftFacts.FilterOptionRef o : f.filterOptions()) {
      if (!lookup.filterOptionExists(o.filterGroupId(), o.option())) {
        p.add(
            DraftProblem.of(
                "FILTER_OPTION_NOT_FOUND",
                "filterOptions",
                o.filterGroupId() + " 에 옵션 '" + o.option() + "' 이 없습니다."));
      }
    }
    return new Result(List.copyOf(p), categories);
  }

  /** 소비기한 보장 일수(ADR-0041): 없으면 보장 없음, 있으면 0~3650. */
  private static void checkShelfLife(@Nullable Integer days, String field, List<DraftProblem> p) {
    if (days != null && (days < 0 || days > 3650)) {
      p.add(
          DraftProblem.of(
              "SHELF_LIFE_INVALID",
              "skus[0].shelfLifeGuarantee." + field,
              "소비기한 보장 일수는 0~3650 입니다: " + days));
    }
  }

  /**
   * SKU 실물 치수(2026-11-16): 없으면 미등록(지어내지 않음), 있으면 세 변 모두 1~5000 mm, 출처는 MEASURED·ESTIMATED 이고 치수와
   * 함께만.
   */
  private static void checkDimensions(SkuDimensions.Input d, List<DraftProblem> p) {
    if (d.empty()) {
      return;
    }
    if (d.widthMm() == null || d.depthMm() == null || d.heightMm() == null) {
      p.add(
          DraftProblem.of(
              "DIMENSIONS_INVALID",
              "skus[0].dimensions",
              d.widthMm() == null && d.depthMm() == null && d.heightMm() == null
                  ? "치수 없이 출처(dimensionsSource)만 있습니다."
                  : "가로·세로·높이는 셋 다 있거나 셋 다 없어야 합니다."));
    } else {
      checkSide(d.widthMm(), "widthMm", p);
      checkSide(d.depthMm(), "depthMm", p);
      checkSide(d.heightMm(), "heightMm", p);
    }
    if (d.source() != null && !SkuDimensions.SOURCES.contains(d.source())) {
      p.add(
          DraftProblem.of(
              "DIMENSIONS_INVALID",
              "skus[0].dimensionsSource",
              "치수 출처는 MEASURED 또는 ESTIMATED 입니다: " + d.source()));
    }
  }

  private static void checkSide(int mm, String field, List<DraftProblem> p) {
    if (mm < SkuDimensions.MIN_MM || mm > SkuDimensions.MAX_MM) {
      p.add(
          DraftProblem.of(
              "DIMENSIONS_INVALID",
              "skus[0].dimensions." + field,
              "치수는 " + SkuDimensions.MIN_MM + "~" + SkuDimensions.MAX_MM + " mm 입니다: " + mm));
    }
  }

  private static void checkBarcodes(
      List<String> barcodes, CatalogLookup lookup, Set<String> ownSkus, List<DraftProblem> p) {
    Set<String> seen = new HashSet<>();
    for (String raw : barcodes) {
      String b = BarcodeType.normalize(raw);
      if (b.isEmpty()) {
        p.add(DraftProblem.of("BARCODE_EMPTY", "skus[0].barcodes", "빈 바코드가 있습니다."));
        continue;
      }
      if (!seen.add(b)) {
        p.add(DraftProblem.of("BARCODE_DUPLICATED_IN_DRAFT", "skus[0].barcodes", b));
        continue;
      }
      if (!BarcodeType.checkDigitValid(b)) {
        p.add(
            DraftProblem.of(
                "BARCODE_CHECK_DIGIT_INVALID",
                "skus[0].barcodes",
                b
                    + " 의 GS1 검사 숫자가 틀립니다(끝자리 "
                    + BarcodeType.checkDigit(b.substring(0, b.length() - 1))
                    + " 이어야 함)."));
        continue;
      }
      Optional<String> owner = lookup.activeBarcodeOwner(b);
      if (owner.isPresent() && !ownSkus.contains(owner.get())) {
        p.add(
            DraftProblem.of(
                "BARCODE_CONFLICT",
                "skus[0].barcodes",
                b + " 는 이미 " + owner.get() + " 에 연결돼 있습니다(자동 선택하지 않음)."));
      }
    }
  }

  /** categoryIds 가 있으면 존재 확인, 없으면 이름 경로로 자동 매핑(ADR-0015). 매핑 실패는 CATEGORY_NOT_MAPPED. */
  private static List<String> resolveCategories(
      DraftFacts f, CatalogLookup lookup, List<DraftProblem> p) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    if (!f.categoryIds().isEmpty() || f.primaryCategoryId() != null) {
      if (f.primaryCategoryId() != null) {
        ids.add(f.primaryCategoryId());
      }
      ids.addAll(f.categoryIds());
      for (String id : ids) {
        if (!lookup.categoryExists(id)) {
          p.add(DraftProblem.of("CATEGORY_NOT_FOUND", "product.categoryIds", id));
        }
      }
    } else {
      for (String path : f.categoryPaths()) {
        Optional<String> id = lookup.categoryByPath(path);
        if (id.isPresent()) {
          ids.add(id.get());
        } else {
          p.add(DraftProblem.of("CATEGORY_NOT_MAPPED", "product.categoryPaths", path));
        }
      }
    }
    if (ids.isEmpty() && p.stream().noneMatch(x -> x.code().startsWith("CATEGORY_"))) {
      p.add(DraftProblem.of("CATEGORY_REQUIRED", "product.categoryIds", "카테고리가 하나 이상 필요합니다."));
    }
    return List.copyOf(ids);
  }

  /** 경로 비교용 정규화: 구분자 '>' 주변 공백을 하나로, 앞뒤 공백 제거. */
  public static String normalizePath(String path) {
    String[] parts = path.split(">");
    List<String> out = new ArrayList<>();
    for (String s : parts) {
      String t = s.strip();
      if (!t.isEmpty()) {
        out.add(t);
      }
    }
    return String.join(" > ", out);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
