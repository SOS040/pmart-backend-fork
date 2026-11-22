package com.pmart.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.search.domain.SearchRules;
import com.pmart.search.infrastructure.CategoryTreeClient.FilterGroup;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterAttributesTest {

  static final FilterGroup WEIGHT_A =
      new FilterGroup("f_a", "중량", true, List.of("200g 이하", "200g ~ 500g", "500g ~ 1kg"));
  static final FilterGroup WEIGHT_B =
      new FilterGroup("f_b", "중량", true, List.of("300g 이하", "300g ~ 500g", "200g ~ 500g"));
  static final FilterGroup TYPE =
      new FilterGroup("f_t", "라면 종류", true, List.of("일반라면", "칼국수/잔치국수"));
  static final FilterGroup DOTTED = new FilterGroup("f_d", "1.5 등급", false, List.of("상"));

  @Test
  void sameNameGroupsBecomeOneRowWithSummedCounts() {
    var rows =
        FilterAttributes.build(
            List.of(
                new ProductSearch.Facet("f_b", "300g 이하", 2),
                new ProductSearch.Facet("f_a", "200g ~ 500g", 3),
                new ProductSearch.Facet("f_b", "200g ~ 500g", 1),
                new ProductSearch.Facet("f_a", "200g 이하", 4)),
            List.of(WEIGHT_A),
            Map.of("f_a", WEIGHT_A, "f_b", WEIGHT_B));
    assertThat(rows).hasSize(1);
    var w = rows.get(0);
    assertThat(w.key()).isEqualTo("n:중량");
    assertThat(w.label()).isEqualTo("중량");
    // 가까운 패널(f_a) 옵션 순서, 그 뒤 다른 그룹에만 있는 옵션
    assertThat(w.values())
        .containsExactly(
            new FilterAttributes.Value("200g 이하", 4),
            new FilterAttributes.Value("200g ~ 500g", 4),
            new FilterAttributes.Value("300g 이하", 2));
  }

  @Test
  void nameFoundInAllPanelsWhenNotNearby() {
    var rows =
        FilterAttributes.build(
            List.of(new ProductSearch.Facet("f_t", "일반라면", 5)), List.of(), Map.of("f_t", TYPE));
    assertThat(rows.get(0).label()).isEqualTo("라면 종류");
    assertThat(rows.get(0).key()).isEqualTo("n:라면 종류");
  }

  @Test
  void unknownGroupKeepsIdAndDottedNameKeepsId() {
    var rows =
        FilterAttributes.build(
            List.of(new ProductSearch.Facet("f_x", "a", 1), new ProductSearch.Facet("f_d", "상", 1)),
            List.of(),
            Map.of("f_d", DOTTED));
    assertThat(rows).extracting(FilterAttributes.Attribute::key).containsExactly("f_x", "f_d");
    assertThat(rows.get(0).label()).isEqualTo("f_x");
    assertThat(rows.get(1).multiSelect()).isFalse();
  }

  @Test
  void nameKeyExpandsToEveryGroupWithThatName() {
    Map<String, String> names = Map.of("f_a", "중량", "f_b", "중량", "f_t", "라면 종류");
    assertThat(SearchRules.filterGroupIds("n:중량", names)).containsExactlyInAnyOrder("f_a", "f_b");
    assertThat(SearchRules.filterGroupIds("f_t", names)).containsExactly("f_t");
    assertThat(SearchRules.filterGroupIds("n:없음", names)).isEmpty();
  }

  @Test
  void originRowHasFixedLabelAndOrder() {
    var rows =
        FilterAttributes.build(
            List.of(
                new ProductSearch.Facet("origin", "수입산", 2),
                new ProductSearch.Facet("origin", "국내산", 5)),
            List.of(WEIGHT_A),
            Map.of());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).key()).isEqualTo("origin");
    assertThat(rows.get(0).label()).isEqualTo("원산지");
    assertThat(rows.get(0).values())
        .containsExactly(
            new FilterAttributes.Value("국내산", 5), new FilterAttributes.Value("수입산", 2));
    assertThat(SearchRules.originTypes(List.of("국내산", "모름"))).containsExactly("DOMESTIC");
  }
}
