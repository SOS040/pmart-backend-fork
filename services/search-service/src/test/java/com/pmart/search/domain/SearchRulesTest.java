package com.pmart.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchRulesTest {

  @Test
  void categoryScopeIncludesAncestors() {
    assertThat(SearchRules.categoryScope(List.of("p08-c01-l01-s01", "t01-c02")))
        .containsExactly("p08", "p08-c01", "p08-c01-l01", "p08-c01-l01-s01", "t01", "t01-c02");
    assertThat(SearchRules.categoryScope(List.of("t01", "t01-c01")))
        .containsExactly("t01", "t01-c01");
  }

  @Test
  void saleStateFromEvent() {
    assertThat(SearchRules.saleStateFromEvent("TEMP_OUT")).isEqualTo("TEMPORARILY_SOLD_OUT");
    assertThat(SearchRules.saleStateFromEvent("DISCONTINUED")).isEqualTo("SALE_STOPPED");
    assertThat(SearchRules.saleStateFromEvent("ON_SALE")).isEqualTo("ON_SALE");
  }

  @Test
  void unsupportedSortFallsBackToRelevance() {
    assertThat(SearchRules.sort("PRICE_ASC")).isEqualTo(SearchRules.Sort.PRICE_ASC);
    assertThat(SearchRules.sort("POPULAR")).isEqualTo(SearchRules.Sort.RELEVANCE);
    assertThat(SearchRules.sort("RATING")).isEqualTo(SearchRules.Sort.RELEVANCE);
    assertThat(SearchRules.sort(null)).isEqualTo(SearchRules.Sort.RELEVANCE);
  }

  @Test
  void cursorRoundTripAndRejectsGarbage() {
    assertThat(SearchRules.offset(SearchRules.cursor(40))).isEqualTo(40);
    assertThat(SearchRules.offset(null)).isZero();
    assertThatThrownBy(() -> SearchRules.offset("not-a-cursor!"))
        .isInstanceOf(SearchRules.InvalidCursorException.class);
  }

  @Test
  void normalizeQuery() {
    assertThat(SearchRules.normalizeQuery("  국산콩   두부 ")).isEqualTo("국산콩 두부");
    assertThat(SearchRules.normalizeQuery("   ")).isNull();
  }
}
