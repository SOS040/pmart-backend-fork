package com.pmart.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.search.domain.SearchRules;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 할인율순(2026-11-22 BR-83, 계약 0.9.0). */
class DiscountSortTest {

  static ProductSearch.Hit hit(String id, ProductSearch.Discount d) {
    return new ProductSearch.Hit(
        id, id, null, List.of(), "E-" + id, 1000L, 1, "ON_SALE", 1, null, List.of(), null, null,
        null, d);
  }

  static ProductSearch.Discount d(int rate, long sale) {
    return new ProductSearch.Discount(sale, rate, "할인", null);
  }

  @Test
  void higherRateFirstThenLowerSalePriceThenIdAndNoDiscountLast() {
    var out =
        ProductSearch.sortByDiscount(
            List.of(
                hit("PRD-A", d(20, 800)),
                hit("PRD-B", null),
                hit("PRD-C", d(30, 700)),
                hit("PRD-D", d(20, 500)),
                hit("PRD-E", d(20, 500))));
    assertThat(out)
        .extracting(ProductSearch.Hit::productId)
        .containsExactly("PRD-C", "PRD-D", "PRD-E", "PRD-A", "PRD-B");
  }

  @Test
  void pageOfSlicesAndEmptyPastTheEnd() {
    var all = List.of(1, 2, 3, 4, 5);
    assertThat(SearchRules.pageOf(all, 0, 2)).containsExactly(1, 2);
    assertThat(SearchRules.pageOf(all, 4, 2)).containsExactly(5);
    assertThat(SearchRules.pageOf(all, 5, 2)).isEmpty();
  }
}
