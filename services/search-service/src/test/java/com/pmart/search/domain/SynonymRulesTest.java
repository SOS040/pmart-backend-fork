package com.pmart.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.search.domain.SynonymRules.Change;
import com.pmart.search.domain.SynonymRules.Outcome;
import com.pmart.search.domain.SynonymRules.Rule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SynonymRulesTest {

  static final List<Rule> CURRENT =
      List.of(new Rule("egg", List.of("계란", "달걀")), new Rule("cola", List.of("콜라", "coke")));

  @Test
  void appliesUpsertAndDeleteWhenVersionMatches() {
    Outcome o =
        SynonymRules.apply(
            CURRENT,
            3,
            3L,
            List.of(
                new Change("egg", "UPSERT", List.of("계란", " 달걀 ", "에그")),
                new Change("cola", "DELETE", null),
                new Change("onion", "UPSERT", List.of("양파", "Onion"))));
    assertThat(o.applied()).isTrue();
    assertThat(o.rules())
        .containsExactly(
            new Rule("egg", List.of("계란", "달걀", "에그")), new Rule("onion", List.of("양파", "onion")));
    assertThat(SynonymRules.esRules(o.rules()))
        .containsExactly(
            Map.of("id", "egg", "synonyms", "계란, 달걀, 에그"),
            Map.of("id", "onion", "synonyms", "양파, onion"));
  }

  @Test
  void staleVersionChangesNothing() {
    Outcome o =
        SynonymRules.apply(CURRENT, 4, 3L, List.of(new Change("x", "UPSERT", List.of("a", "b"))));
    assertThat(o.reason()).isEqualTo("STALE_VERSION");
    assertThat(o.rules()).isEqualTo(CURRENT);
    assertThat(SynonymRules.apply(CURRENT, 0, null, List.of()).reason()).isEqualTo("STALE_VERSION");
  }

  @Test
  void anyBadLineRejectsAll() {
    Outcome o =
        SynonymRules.apply(
            CURRENT,
            1,
            1L,
            List.of(
                new Change("ok", "UPSERT", List.of("a", "b")),
                new Change("ghost", "DELETE", null),
                new Change("dup", "UPSERT", List.of("a", "A")),
                new Change("weird", "RENAME", null),
                new Change("ok", "DELETE", null)));
    assertThat(o.applied()).isFalse();
    assertThat(o.reason()).isEqualTo("LINE_REJECTED");
    assertThat(o.lineReasons())
        .isEqualTo(
            Map.of(
                "ghost", "RULE_NOT_FOUND",
                "dup", "INVALID_TERMS",
                "weird", "INVALID_OP",
                "ok", "DUPLICATE_RULE"));
    assertThat(o.rules()).isEqualTo(CURRENT);
  }

  @Test
  void normalize() {
    assertThat(SynonymRules.normalize(List.of(" Coke ", "콜라"))).containsExactly("coke", "콜라");
    assertThat(SynonymRules.normalize(List.of("a"))).isNull();
    assertThat(SynonymRules.normalize(List.of("a", "b, c"))).isNull();
    assertThat(SynonymRules.normalize(List.of("a", "a => b"))).isNull();
    assertThat(SynonymRules.normalize(List.of("a", "x".repeat(31)))).isNull();
  }
}
