package com.pmart.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.product.domain.CategoryTree.Level;
import com.pmart.product.domain.CategoryTree.SourceFilter;
import com.pmart.product.domain.CategoryTree.SourceNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryTreeAndSkuTest {

  static SourceNode node(
      String id, String name, String level, List<String> filters, SourceNode... children) {
    return new SourceNode(
        id, name, level, "top".equals(level) ? "맛있는것" : null, filters, List.of(children));
  }

  static final List<SourceFilter> FILTERS =
      List.of(
          new SourceFilter("f1", "추천", "toggle", true, List.of("최저가 도전")),
          new SourceFilter("f2", "품목", "multiple", false, List.of("국", "탕")));

  @Test
  void optionalSourceFieldsMayBeMissing() {
    // 계약상 선택인 filters·children·common 이 JSON 에 없으면 null 로 들어온다 → 빈 목록·false
    var tree =
        CategoryTree.parse(
            List.of(
                new SourceNode(
                    "t01",
                    "간편식",
                    "top",
                    "맛있는것",
                    List.of("g1"),
                    List.of(new SourceNode("t01-c01", "국", "sub", null, null, null)))),
            List.of(new SourceFilter("g1", "보관방법", "multiple", null, List.of("냉장"))));
    assertThat(tree.nodes()).hasSize(2);
    assertThat(tree.filterGroups().get(0).common()).isFalse();
  }

  @Test
  void parsesFourLevelsWithNamePathsAndFilterOrder() {
    var tree =
        CategoryTree.parse(
            List.of(
                node(
                    "p03",
                    "국·탕·찌개",
                    "top",
                    List.of("f1", "f2"),
                    node(
                        "p03-c01",
                        "국·국밥",
                        "sub",
                        List.of("f2"),
                        node(
                            "p03-c01-l01",
                            "국",
                            "chip1",
                            List.of(),
                            node("p03-c01-l01-s01", "미역국", "chip2", List.of()))))),
            FILTERS);
    assertThat(tree.nodes())
        .extracting(CategoryTree.Node::path)
        .containsExactly("국·탕·찌개", "국·탕·찌개 > 국·국밥", "국·탕·찌개 > 국·국밥 > 국", "국·탕·찌개 > 국·국밥 > 국 > 미역국");
    assertThat(tree.nodes().get(0).section()).isEqualTo("맛있는것");
    assertThat(tree.nodes().get(1).section()).isNull();
    assertThat(tree.countsByLevel()).containsEntry(Level.TOP, 1).containsEntry(Level.CHIP2, 1);
    assertThat(tree.nodeFilters())
        .filteredOn(f -> f.categoryId().equals("p03"))
        .extracting(CategoryTree.NodeFilter::filterGroupId)
        .containsExactly("f1", "f2");
    assertThat(tree.filterGroups().get(0).selectionMode())
        .isEqualTo(CategoryTree.SelectionMode.TOGGLE);
  }

  @Test
  void additionsAddOnlyMissingNodesAndKeepExistingOnes() {
    var base =
        CategoryTree.parse(
            List.of(
                node(
                    "p03",
                    "국·탕·찌개",
                    "top",
                    List.of("f1"),
                    node("p03-c01", "국·국밥", "sub", List.of("f2")))),
            FILTERS);
    var added =
        CategoryTree.withAdditions(
            base.nodes(),
            base.nodeFilters(),
            base.filterGroups(),
            List.of(
                new CategoryTree.Addition(List.of("p03", "p03-c02"), List.of("국·탕·찌개", "탕 · 찌개")),
                new CategoryTree.Addition(
                    List.of("p09", "p09-c01", "p09-c01-l01"),
                    List.of("음료·커피·생수", "탄산음료", "콜라·사이다")),
                new CategoryTree.Addition(
                    List.of("p09", "p09-c01", "p09-c01-l01"),
                    List.of("음료·커피·생수", "탄산음료", "콜라·사이다"))));
    assertThat(added.addedIds()).containsExactly("p03-c02", "p09", "p09-c01", "p09-c01-l01");
    var c02 =
        added.tree().nodes().stream()
            .filter(n -> n.categoryId().equals("p03-c02"))
            .findFirst()
            .orElseThrow();
    assertThat(c02.parentId()).isEqualTo("p03");
    assertThat(c02.level()).isEqualTo(Level.SUB);
    assertThat(c02.sortOrder()).isEqualTo(1); // 기존 형제(p03-c01) 다음
    assertThat(c02.path()).isEqualTo("국·탕·찌개 > 탕 · 찌개");
    assertThat(added.tree().nodeFilters()).isEqualTo(base.nodeFilters());
    assertThat(added.tree().countsByLevel())
        .containsEntry(Level.CHIP1, 1)
        .containsEntry(Level.TOP, 2);
  }

  @Test
  void additionsRejectRenamedIdsSamePathOtherIdAndBadPrefix() {
    var base =
        CategoryTree.parse(
            List.of(
                node("p03", "국·탕·찌개", "top", List.of(), node("p03-c01", "국·국밥", "sub", List.of()))),
            FILTERS);
    assertThatThrownBy(
            () ->
                CategoryTree.withAdditions(
                    base.nodes(),
                    base.nodeFilters(),
                    base.filterGroups(),
                    List.of(
                        new CategoryTree.Addition(
                            List.of("p03", "p03-c01"), List.of("국·탕·찌개", "다른 이름")),
                        new CategoryTree.Addition(
                            List.of("p03", "p03-c09"), List.of("국 · 탕 · 찌개", "국·국밥")),
                        new CategoryTree.Addition(List.of("p09", "x01"), List.of("음료", "탄산")),
                        new CategoryTree.Addition(List.of("p09"), List.of("음료", "탄산")))))
        .isInstanceOf(CategoryTree.InvalidTreeException.class)
        .satisfies(
            e ->
                assertThat(((CategoryTree.InvalidTreeException) e).errors())
                    .anyMatch(s -> s.contains("이름이 다릅니다"))
                    .anyMatch(s -> s.contains("같은 이름 경로가 다른 id"))
                    .anyMatch(s -> s.contains("상위 id 로 시작하지"))
                    .anyMatch(s -> s.contains("단계 수 오류")));
  }

  @Test
  void rejectsWrongLevelOrderUnknownFilterAndDuplicateIds() {
    assertThatThrownBy(
            () ->
                CategoryTree.parse(
                    List.of(
                        node(
                            "p01",
                            "A",
                            "top",
                            List.of("nope"),
                            node("p01-x", "B", "chip1", List.of())),
                        node("p01", "C", "top", List.of())),
                    FILTERS))
        .isInstanceOf(CategoryTree.InvalidTreeException.class)
        .satisfies(
            e ->
                assertThat(((CategoryTree.InvalidTreeException) e).errors())
                    .anyMatch(s -> s.contains("단계 순서"))
                    .anyMatch(s -> s.contains("필터 그룹이 정의에 없습니다"))
                    .anyMatch(s -> s.contains("id 중복")));
  }

  @Test
  void skuFormatSharesOneSerialAcrossEAndB() {
    assertThat(SkuCodes.format("E", 1)).isEqualTo("E-00000001");
    assertThat(SkuCodes.format("B", 2)).isEqualTo("B-00000002");
    assertThat(SkuCodes.isValid("P-00000001")).isFalse();
    assertThatThrownBy(() -> SkuCodes.format("E", 100_000_000L))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void barcodeKeepsLeadingZerosAndClassifiesByLength() {
    assertThat(BarcodeType.normalize(" 0012345678905 ")).isEqualTo("0012345678905");
    assertThat(BarcodeType.classify("0012345678905")).isEqualTo(BarcodeType.GTIN_13);
    assertThat(BarcodeType.classify("ABC-1")).isEqualTo(BarcodeType.OTHER);
  }
}
