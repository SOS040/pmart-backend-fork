package com.pmart.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 멱등 해시·출처 검사·지위 태그·생애 제외 규칙. */
class SmallRulesTest {

  // ---- 멱등 해시 ----

  @Test
  void hashIgnoresWhitespaceAndNormalizesNfc() {
    String decomposed = "취소 절차".replace("취", "취"); // 자모 분해형
    assertThat(RequestHashing.requestHash("  취소   절차 ", null, null))
        .isEqualTo(RequestHashing.requestHash(decomposed, null, null));
  }

  @Test
  void contextKeyOrderAndEmptyValuesDoNotMatter() {
    assertThat(RequestHashing.normalizedContext("orders", "MFC-1"))
        .isEqualTo("{\"centerId\":\"MFC-1\",\"screen\":\"orders\"}");
    assertThat(RequestHashing.normalizedContext(null, " ")).isEqualTo("{}");
    assertThat(RequestHashing.requestHash("q", null, null))
        .isEqualTo(RequestHashing.requestHash("q", "", "  "));
  }

  @Test
  void differentContextChangesHash() {
    assertThat(RequestHashing.requestHash("q", "orders", null))
        .isNotEqualTo(RequestHashing.requestHash("q", "claims", null));
  }

  // ---- X-Source-System ----

  @Test
  void tokenSourceMustMatchHeader() {
    assertThat(SourceSystemPolicy.accepts("backoffice-web", "backoffice-web")).isTrue();
    assertThat(SourceSystemPolicy.accepts("backoffice-web", "ops-console")).isFalse();
    assertThat(SourceSystemPolicy.accepts("ops-console", "ops-console")).isTrue();
  }

  @Test
  void withoutTokenSourceOnlyBackofficeWebIsAccepted() {
    assertThat(SourceSystemPolicy.accepts(null, "backoffice-web")).isTrue();
    assertThat(SourceSystemPolicy.accepts(null, "ops-console")).isFalse();
    assertThat(SourceSystemPolicy.accepts("", "customer-app")).isFalse();
    assertThat(SourceSystemPolicy.accepts(null, null)).isFalse();
  }

  // ---- 지위 태그 ----

  @Test
  void leadingTagsMapToAuthority() {
    assertThat(Authority.fromLeadingTag("[확정 정책] 봉투값은 100원")).contains(Authority.CONFIRMED);
    assertThat(Authority.fromLeadingTag("`[확정 정책]` 기본가격")).contains(Authority.CONFIRMED);
    assertThat(Authority.fromLeadingTag("3. `[확정 정책]` 구매 제한")).contains(Authority.CONFIRMED);
    assertThat(Authority.fromLeadingTag("**[보완 설계] 문서 해석 규칙:**"))
        .contains(Authority.ADOPTED_DESIGN);
    assertThat(Authority.fromLeadingTag("[채택 설계] 변경 시")).contains(Authority.ADOPTED_DESIGN);
    assertThat(Authority.fromLeadingTag("[설계안] OMS는")).contains(Authority.PROPOSAL);
    assertThat(Authority.fromLeadingTag("[가정] 용량")).contains(Authority.ASSUMPTION);
    assertThat(Authority.fromLeadingTag("[팀 확인 필요] 값")).contains(Authority.UNDECIDED);
    assertThat(Authority.fromLeadingTag("[결정 필요] 값")).contains(Authority.UNDECIDED);
    assertThat(Authority.fromLeadingTag("[외부 참고값] 값")).contains(Authority.EXTERNAL_REFERENCE);
    assertThat(Authority.fromLeadingTag("본문 중간의 [확정 정책] 언급")).isEmpty();
  }

  @Test
  void adrStatusLine() {
    assertThat(Authority.fromAdrStatus("- 상태: 승인 (2026-10-31, …)")).isEqualTo(Authority.CONFIRMED);
    assertThat(Authority.fromAdrStatus("- 상태: 제안 (2026-10-31, 초안 — 사용자 검토 전)"))
        .isEqualTo(Authority.PROPOSAL);
    assertThat(Authority.fromAdrStatus("- 상태: 일부 대체됨 by ADR-0026")).isEqualTo(Authority.PROPOSAL);
  }

  // ---- 생애 제외 ----

  @Test
  void currentQuestionsExcludeSupersededAndPhase1Excluded() {
    assertThat(Lifecycle.excludedFor(false))
        .containsExactlyInAnyOrder(Lifecycle.SUPERSEDED, Lifecycle.EXCLUDED_FROM_PHASE1);
  }

  @Test
  void historyQuestionsReadmitOnlySuperseded() {
    assertThat(Lifecycle.excludedFor(true)).isEqualTo(List.of(Lifecycle.EXCLUDED_FROM_PHASE1));
  }
}
