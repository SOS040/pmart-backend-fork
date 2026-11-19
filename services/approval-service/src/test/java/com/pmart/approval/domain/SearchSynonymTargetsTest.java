package com.pmart.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.approval.domain.SearchSynonymTargets.Line;
import com.pmart.common.web.PmartException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchSynonymTargetsTest {

  static final String SVC = "search-service";
  static final String REF = "pmart-product-synonyms";

  @Test
  void acceptsUpsertAndDelete() {
    assertThatCode(
            () ->
                SearchSynonymTargets.validate(
                    SVC,
                    REF,
                    0,
                    List.of(
                        new Line("egg", "UPSERT", List.of("계란", "달걀")),
                        new Line("old-1", "DELETE", null))))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsWrongTargetAndMissingVersion() {
    assertThatThrownBy(
            () ->
                SearchSynonymTargets.validate(
                    "product-service",
                    "x",
                    null,
                    List.of(new Line("egg", "UPSERT", List.of("계란", "달걀")))))
        .isInstanceOfSatisfying(
            PmartException.class,
            e ->
                assertThat(e.details())
                    .extracting(PmartException.Detail::field)
                    .containsExactly("targetService", "targetRef", "targetExpectedVersion"));
  }

  @Test
  void rejectsBadLines() {
    assertThatThrownBy(
            () ->
                SearchSynonymTargets.validate(
                    SVC,
                    REF,
                    3,
                    List.of(
                        new Line("계란", "UPSERT", List.of("계란", "달걀")),
                        new Line("a", "UPSERT", List.of("계란")),
                        new Line("a", "DELETE", null),
                        new Line("b", "RENAME", null),
                        new Line("c", "UPSERT", List.of("콜라", "콜라 => 코카콜라")))))
        .isInstanceOfSatisfying(
            PmartException.class,
            e ->
                assertThat(e.details())
                    .extracting(PmartException.Detail::field)
                    .containsExactly(
                        "lines[0].lineKey",
                        "lines[1].attributes.terms",
                        "lines[2].lineKey",
                        "lines[3].attributes.op",
                        "lines[4].attributes.terms"));
    assertThatThrownBy(() -> SearchSynonymTargets.validate(SVC, REF, 0, List.of()))
        .isInstanceOf(PmartException.class);
  }

  @Test
  void termsProblem() {
    assertThat(SearchSynonymTargets.termsProblem(List.of("계란", "달걀"))).isNull();
    assertThat(SearchSynonymTargets.termsProblem(List.of("Cola", "cola"))).contains("두 번");
    assertThat(SearchSynonymTargets.termsProblem(List.of("계란", " "))).contains("1~30");
    assertThat(SearchSynonymTargets.termsProblem(null)).contains("2~20");
  }

  @Test
  void requesterRoles() {
    assertThatCode(() -> ApprovalRules.requireRequesterRole("SEARCH_SYNONYM", List.of("MD"), null))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                ApprovalRules.requireRequesterRole(
                    "SEARCH_SYNONYM", List.of("MD"), List.of("MD", "OPERATOR_ADMIN")))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                ApprovalRules.requireRequesterRole(
                    "SEARCH_SYNONYM", List.of("PRICE_APPROVER"), List.of("MD", "OPERATOR_ADMIN")))
        .isInstanceOfSatisfying(
            PmartException.class, e -> assertThat(e.status().value()).isEqualTo(403));
  }
}
