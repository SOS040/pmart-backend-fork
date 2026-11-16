package com.pmart.product.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DraftRulesTest {

  /** 발효 트리 "국·탕·찌개(p03) > 국·국밥(p03-c01)", 바코드 8801111000017 은 E-00000009 소유. */
  static final CatalogLookup LOOKUP =
      new CatalogLookup() {
        final Map<String, String> paths = Map.of("국·탕·찌개", "p03", "국·탕·찌개 > 국·국밥", "p03-c01");

        @Override
        public boolean categoryExists(String id) {
          return paths.containsValue(id);
        }

        @Override
        public Optional<String> categoryByPath(String path) {
          return Optional.ofNullable(paths.get(DraftRules.normalizePath(path)));
        }

        @Override
        public boolean filterOptionExists(String g, String o) {
          return "filter_a".equals(g) && "냉동".equals(o);
        }

        @Override
        public Optional<String> activeBarcodeOwner(String b) {
          return "8801111000017".equals(b) ? Optional.of("E-00000009") : Optional.empty();
        }

        @Override
        public boolean baseProductExists(String id) {
          return "BP-1".equals(id);
        }
      };

  static final SkuDimensions.Input NO_DIMENSIONS = new SkuDimensions.Input(null, null, null, null);

  static DraftFacts facts(
      Integer weight, String storage, List<String> barcodes, List<String> paths) {
    return new DraftFacts(
        "묵은지부대찌개 180g",
        null,
        "E",
        1,
        weight,
        storage,
        null,
        null,
        NO_DIMENSIONS,
        barcodes,
        null,
        List.of(),
        paths,
        List.of());
  }

  @Test
  void validDraftMapsCategoryByNamePath() {
    var r =
        DraftRules.validate(
            facts(180, "FROZEN", List.of("0880123"), List.of("국·탕·찌개  >국·국밥")), LOOKUP, Set.of());
    assertThat(r.valid()).isTrue();
    assertThat(r.categoryIds()).containsExactly("p03-c01");
  }

  @Test
  void shelfLifeGuaranteeIsOptionalButBounded() {
    var none =
        DraftRules.validate(facts(180, "CHILLED", List.of(), List.of("국·탕·찌개")), LOOKUP, Set.of());
    assertThat(none.valid()).isTrue();
    var ok =
        DraftRules.validate(
            new DraftFacts(
                "x",
                null,
                "E",
                1,
                100,
                "CHILLED",
                5,
                3,
                NO_DIMENSIONS,
                List.of(),
                "p03",
                List.of(),
                List.of(),
                List.of()),
            LOOKUP,
            Set.of());
    assertThat(ok.valid()).isTrue();
    var bad =
        DraftRules.validate(
            new DraftFacts(
                "x",
                null,
                "E",
                1,
                100,
                "CHILLED",
                -1,
                4000,
                NO_DIMENSIONS,
                List.of(),
                "p03",
                List.of(),
                List.of(),
                List.of()),
            LOOKUP,
            Set.of());
    assertThat(bad.problems())
        .extracting(DraftProblem::field)
        .containsExactlyInAnyOrder(
            "skus[0].shelfLifeGuarantee.minRemainingDays",
            "skus[0].shelfLifeGuarantee.maxDaysSinceManufacture");
  }

  static DraftFacts withDimensions(SkuDimensions.Input d) {
    return new DraftFacts(
        "x", null, "E", 1, 100, "CHILLED", null, null, d, List.of(), "p03", List.of(), List.of(),
        List.of());
  }

  @Test
  void dimensionsAreOptionalButAllThreeInRange() {
    // 없으면 치수 미등록(지어내지 않음)
    assertThat(DraftRules.validate(withDimensions(NO_DIMENSIONS), LOOKUP, Set.of()).valid())
        .isTrue();
    assertThat(
            DraftRules.validate(
                    withDimensions(new SkuDimensions.Input(70, 70, 200, "ESTIMATED")),
                    LOOKUP,
                    Set.of())
                .valid())
        .isTrue();
    // 출처는 선택
    assertThat(
            DraftRules.validate(
                    withDimensions(new SkuDimensions.Input(1, 5000, 3, null)), LOOKUP, Set.of())
                .valid())
        .isTrue();
    // 일부만
    assertThat(
            DraftRules.validate(
                    withDimensions(new SkuDimensions.Input(70, null, 200, "MEASURED")),
                    LOOKUP,
                    Set.of())
                .problems())
        .extracting(DraftProblem::code, DraftProblem::field)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("DIMENSIONS_INVALID", "skus[0].dimensions"));
    // 범위 밖
    assertThat(
            DraftRules.validate(
                    withDimensions(new SkuDimensions.Input(0, 5001, 10, null)), LOOKUP, Set.of())
                .problems())
        .extracting(DraftProblem::field)
        .containsExactlyInAnyOrder("skus[0].dimensions.widthMm", "skus[0].dimensions.depthMm");
    // 출처만 / 모르는 출처
    assertThat(
            DraftRules.validate(
                    withDimensions(new SkuDimensions.Input(null, null, null, "MEASURED")),
                    LOOKUP,
                    Set.of())
                .problems())
        .extracting(DraftProblem::code)
        .containsExactly("DIMENSIONS_INVALID");
    assertThat(
            DraftRules.validate(
                    withDimensions(new SkuDimensions.Input(10, 10, 10, "GUESS")), LOOKUP, Set.of())
                .problems())
        .extracting(DraftProblem::field)
        .containsExactly("skus[0].dimensionsSource");
  }

  @Test
  void missingWeightAndStorageAreNotFilledWithDefaults() {
    var r = DraftRules.validate(facts(null, null, List.of(), List.of("국·탕·찌개")), LOOKUP, Set.of());
    assertThat(r.problems())
        .extracting(DraftProblem::code)
        .containsExactlyInAnyOrder("WEIGHT_REQUIRED", "STORAGE_TYPE_REQUIRED");
  }

  @Test
  void unmappedCategoryPathIsReported() {
    var r =
        DraftRules.validate(facts(180, "FROZEN", List.of(), List.of("없는 분류")), LOOKUP, Set.of());
    assertThat(r.problems()).extracting(DraftProblem::code).containsExactly("CATEGORY_NOT_MAPPED");
  }

  @Test
  void barcodeOwnedByAnotherSkuIsConflictButOwnSkuIsNot() {
    var other =
        DraftRules.validate(
            facts(180, "FROZEN", List.of("8801111000017"), List.of("국·탕·찌개")), LOOKUP, Set.of());
    assertThat(other.problems()).extracting(DraftProblem::code).containsExactly("BARCODE_CONFLICT");
    var own =
        DraftRules.validate(
            facts(180, "FROZEN", List.of("8801111000017"), List.of("국·탕·찌개")),
            LOOKUP,
            Set.of("E-00000009"));
    assertThat(own.valid()).isTrue();
  }

  @Test
  void duplicateBarcodeInDraftAndUnitsRules() {
    var dup =
        DraftRules.validate(
            facts(180, "FROZEN", List.of("123", "123"), List.of("국·탕·찌개")), LOOKUP, Set.of());
    assertThat(dup.problems())
        .extracting(DraftProblem::code)
        .containsExactly("BARCODE_DUPLICATED_IN_DRAFT");
    var e6 =
        DraftRules.validate(
            new DraftFacts(
                "x",
                null,
                "E",
                6,
                100,
                "AMBIENT",
                null,
                null,
                NO_DIMENSIONS,
                List.of(),
                "p03",
                List.of(),
                List.of(),
                List.of()),
            LOOKUP,
            Set.of());
    assertThat(e6.problems())
        .extracting(DraftProblem::code)
        .containsExactly("UNITS_PER_SKU_INVALID");
  }

  @Test
  void explicitCategoryIdsWinAndPrimaryComesFirst() {
    var r =
        DraftRules.validate(
            new DraftFacts(
                "x",
                "BP-1",
                "B",
                6,
                900,
                "AMBIENT",
                null,
                null,
                NO_DIMENSIONS,
                List.of(),
                "p03-c01",
                List.of("p03", "p03-c01"),
                List.of("무시"),
                List.of()),
            LOOKUP,
            Set.of());
    assertThat(r.valid()).isTrue();
    assertThat(r.categoryIds()).containsExactly("p03-c01", "p03");
  }

  @Test
  void unknownBaseProductAndFilterOption() {
    var r =
        DraftRules.validate(
            new DraftFacts(
                "x",
                "BP-404",
                "E",
                1,
                1,
                "AMBIENT",
                null,
                null,
                NO_DIMENSIONS,
                List.of(),
                "p03",
                List.of(),
                List.of(),
                List.of(new DraftFacts.FilterOptionRef("filter_a", "상온"))),
            LOOKUP,
            Set.of());
    assertThat(r.problems())
        .extracting(DraftProblem::code)
        .containsExactlyInAnyOrder("BASE_PRODUCT_NOT_FOUND", "FILTER_OPTION_NOT_FOUND");
  }
}
