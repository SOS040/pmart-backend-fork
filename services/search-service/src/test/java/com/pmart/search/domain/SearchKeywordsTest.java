package com.pmart.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.search.domain.SearchKeywords.KeywordCount;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchKeywordsTest {

  @Test
  void normalizeTrimsCollapsesAndKeepsChosung() {
    assertThat(SearchKeywords.normalize("  생   크림 ")).isEqualTo("생 크림");
    assertThat(SearchKeywords.normalize("ㄷㅂ")).isEqualTo("ㄷㅂ");
    assertThat(SearchKeywords.normalize("   ")).isNull();
    assertThat(SearchKeywords.normalize(null)).isNull();
  }

  @Test
  void normalizeCutsAt100CodePoints() {
    String longKeyword = "가".repeat(120);
    assertThat(SearchKeywords.normalize(longKeyword)).hasSize(100);
    // 서로게이트 쌍(이모지)을 반으로 자르지 않는다
    String emoji = "😀".repeat(101);
    String cut = SearchKeywords.normalize(emoji);
    assertThat(cut.codePointCount(0, cut.length())).isEqualTo(100);
  }

  @Test
  void searcherHashIsStableAndHidesSubject() {
    String a = SearchKeywords.searcherHash("k1", "CUSTOMER", "cust-1");
    assertThat(a).isEqualTo(SearchKeywords.searcherHash("k1", "CUSTOMER", "cust-1"));
    assertThat(a).hasSize(64).doesNotContain("cust-1");
    // 종류가 다르면 같은 주체 ID 라도 다른 검색자
    assertThat(a).isNotEqualTo(SearchKeywords.searcherHash("k1", "GUEST", "cust-1"));
    assertThat(SearchKeywords.recentDocId(a, "두부"))
        .isEqualTo(SearchKeywords.recentDocId(a, "두부"))
        .isNotEqualTo(SearchKeywords.recentDocId(a, "우유"));
    // 비밀키가 다르면 다른 해시 — 키 없이는 ID 를 짐작해 맞춰 볼 수 없다
    assertThat(SearchKeywords.searcherHash("k2", "CUSTOMER", "cust-1")).isNotEqualTo(a);
  }

  @Test
  void popularNeedsDistinctSearchersExcludesHiddenAndOrdersByCount() {
    List<KeywordCount> counted =
        List.of(
            new KeywordCount("혼자 반복", 50, 1),
            new KeywordCount("두부", 5, 3),
            new KeywordCount("우유", 7, 2),
            new KeywordCount("나쁜말", 9, 4),
            new KeywordCount("계란", 5, 4),
            new KeywordCount("라면", 2, 2));

    assertThat(SearchKeywords.popular(counted, 10, 2, List.of("  나쁜말 ")))
        .containsExactly("우유", "계란", "두부", "라면");
    assertThat(SearchKeywords.popular(counted, 2, 2, List.of())).containsExactly("나쁜말", "우유");
    assertThat(SearchKeywords.popular(counted, 10, 4, List.of())).containsExactly("나쁜말", "계란");
  }

  @Test
  void hiddenMatchIgnoresCaseAndSpacing() {
    assertThat(
            SearchKeywords.popular(
                List.of(new KeywordCount("Bad  Word", 3, 3)), 10, 2, List.of("bad word")))
        .isEmpty();
  }
}
