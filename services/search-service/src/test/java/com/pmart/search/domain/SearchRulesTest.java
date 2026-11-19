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

  @Test
  void chosungQueryIsOnlyConsonantsTwoOrMore() {
    assertThat(SearchRules.isChosungQuery("ㄷㅂ")).isTrue();
    assertThat(SearchRules.isChosungQuery("ㅅㅋㄹ")).isTrue();
    assertThat(SearchRules.isChosungQuery("ㅁㅅ ㅇㅇㅍ")).isTrue(); // 띄어 써도 초성만이면
    assertThat(SearchRules.isChosungQuery("ㄲㅂ")).isTrue(); // 겹자음
    assertThat(SearchRules.isChosungQuery("ㄷ")).isFalse(); // 1자는 일반 검색
    assertThat(SearchRules.isChosungQuery("ㄷ부")).isFalse(); // 섞인 검색어
    assertThat(SearchRules.isChosungQuery("두부")).isFalse();
    assertThat(SearchRules.isChosungQuery("ㅏㅓ")).isFalse(); // 모음
    assertThat(SearchRules.isChosungQuery("ab")).isFalse();
    assertThat(SearchRules.isChosungQuery(null)).isFalse();
  }

  /** 깊은 쪽 상한(2026-11-19 1순위 ①): 창(1만) 밖 커서는 잘못된 커서, 창 끝에서는 크기를 줄이고 다음 커서를 주지 않는다. */
  @Test
  void cursorStaysInsideTheResultWindow() {
    assertThat(SearchRules.offset(SearchRules.cursor(9_999))).isEqualTo(9_999);
    assertThatThrownBy(() -> SearchRules.offset(SearchRules.cursor(SearchRules.MAX_WINDOW)))
        .isInstanceOf(SearchRules.InvalidCursorException.class);
    assertThatThrownBy(() -> SearchRules.offset(SearchRules.cursor(20_000)))
        .isInstanceOf(SearchRules.InvalidCursorException.class);
    assertThat(SearchRules.pageSize(0, 50)).isEqualTo(50);
    assertThat(SearchRules.pageSize(9_980, 50)).isEqualTo(20);
    assertThat(SearchRules.hasNext(9_000, 50_000, true)).isTrue();
    assertThat(SearchRules.hasNext(10_000, 50_000, true)).as("창 끝 — 다음 없음").isFalse();
    assertThat(SearchRules.hasNext(40, 40, true)).isFalse();
    assertThat(SearchRules.hasNext(10, 40, false)).isFalse();
  }
}
