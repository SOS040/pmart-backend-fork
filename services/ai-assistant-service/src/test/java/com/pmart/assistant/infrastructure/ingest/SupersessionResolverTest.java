package com.pmart.assistant.infrastructure.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.assistant.domain.Authority;
import com.pmart.assistant.domain.Lifecycle;
import com.pmart.assistant.infrastructure.ingest.SupersessionResolver.Relation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 대체 활성화 규칙(검토 1) — 실제 ADR "- 대체:" 줄로 회귀를 고정한다. */
class SupersessionResolverTest {

  private static final Map<String, String> ADRS =
      Map.of(
          "0011",
          "adr/0011",
          "0019",
          "adr/0019",
          "0021",
          "adr/0021",
          "0024",
          "adr/0024",
          "0025",
          "adr/0025",
          "0026",
          "adr/0026",
          "0027",
          "adr/0027",
          "0029",
          "adr/0029",
          "0030",
          "adr/0030",
          "0031",
          "adr/0031");

  @Test
  void approvedAdrSupersedesOnlyNamedDecisions_0026_to_0011() {
    var rel =
        SupersessionResolver.parse(
            "adr/0026",
            Authority.CONFIRMED,
            List.of(
                "- 대체: ADR-0011 의 결정 3(서비스 토큰 발급)·5(호출 측 서비스 토큰)와 \"`/internal/**` 는 TYPE_SERVICE 토큰\" 규칙. ADR-0011 의 결정 1(RS256 서명키)·2(JWKS)는 사용자 토큰 검증용으로 유지한다."),
            ADRS);
    assertThat(rel)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.targetPath()).isEqualTo("adr/0011");
              assertThat(r.decisions()).containsExactly(3, 5); // 뒤쪽 "결정 1·2 유지"를 대체로 읽지 않는다
              assertThat(r.active()).isTrue();
            });
    assertThat(SupersessionResolver.apply(Lifecycle.ACTIVE, 3, rel).lifecycle())
        .isEqualTo(Lifecycle.SUPERSEDED);
    assertThat(SupersessionResolver.apply(Lifecycle.ACTIVE, 3, rel).supersededBy())
        .isEqualTo("adr/0026");
    assertThat(SupersessionResolver.apply(Lifecycle.ACTIVE, 1, rel).lifecycle())
        .isEqualTo(Lifecycle.ACTIVE);
    assertThat(SupersessionResolver.apply(Lifecycle.ACTIVE, null, rel).lifecycle())
        .isEqualTo(Lifecycle.ACTIVE);
  }

  @Test
  void proposedAdrNeverSupersedes_0025_to_0019() {
    var rel =
        SupersessionResolver.parse(
            "adr/0025",
            Authority.PROPOSAL,
            List.of(
                "- 대체: ADR-0019 결정 1(앱 ID 토큰 직접 전달)·8(백엔드는 client secret 을 쓰지 않음). ADR-0019 의 계정 연결·동의 생략·토큰·실패 제한·재전송·이벤트(결정 2~7)는 그대로 유지한다."),
            ADRS);
    assertThat(rel)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.decisions()).containsExactly(1, 8);
              assertThat(r.active()).isFalse();
            });
    var applied = SupersessionResolver.apply(Lifecycle.ACTIVE, 1, rel);
    assertThat(applied.lifecycle()).isEqualTo(Lifecycle.SUPERSESSION_PROPOSED);
    assertThat(applied.proposedSupersededBy()).containsExactly("adr/0025");
    assertThat(SupersessionResolver.apply(Lifecycle.ACTIVE, 2, rel).lifecycle())
        .isEqualTo(Lifecycle.ACTIVE);
  }

  @Test
  void noDecisionNumbersMeansProposedForWholeTarget_0024_to_0019() {
    var rel =
        SupersessionResolver.parse(
            "adr/0024",
            Authority.PROPOSAL,
            List.of("- 대체: ADR-0019 의 \"제공자 토큰·이메일은 저장하지 않는다\" 구현 해석(검증 결과에서 `sub` 만 꺼내던 것)"),
            ADRS);
    assertThat(rel).singleElement().satisfies(r -> assertThat(r.decisions()).isEmpty());
    assertThat(SupersessionResolver.apply(Lifecycle.ACTIVE, null, rel).lifecycle())
        .isEqualTo(Lifecycle.SUPERSESSION_PROPOSED);
  }

  @Test
  void approvedWithoutDecisionNumbersIsNotActive() {
    List<Relation> rel =
        SupersessionResolver.parse(
            "adr/0026", Authority.CONFIRMED, List.of("- 대체: ADR-0011 의 일부 규칙"), ADRS);
    assertThat(rel.getFirst().active()).isFalse();
    assertThat(SupersessionResolver.apply(Lifecycle.ACTIVE, 3, rel).lifecycle())
        .isEqualTo(Lifecycle.SUPERSESSION_PROPOSED);
  }

  @Test
  void multipleTargetsAndRanges_0031() {
    var rel =
        SupersessionResolver.parse(
            "adr/0031",
            Authority.CONFIRMED,
            List.of(
                "- 대체: ADR-0021 결정 1(OpenSearch)·2(search-service 가 직접 색인 — 색인 주체만 Logstash 로)·3(n-gram 만, nori 없음)·4(search-service 의 Painless 갱신 — 같은 스크립트를 Logstash 가 실행). ADR-0030 결정 2(Fluent Bit → OpenSearch, OpenSearch Dashboards)·4(OpenSearch 단일 노드 복제본). ADR-0021 결정 5~8, ADR-0030 결정 1·3(지표·디스크 감시·dead_letter)은 유지."),
            ADRS);
    assertThat(rel).extracting(Relation::targetPath).containsExactly("adr/0021", "adr/0030");
    assertThat(rel.get(0).decisions()).containsExactly(1, 2, 3, 4);
    assertThat(rel.get(1).decisions()).containsExactly(2, 4);
    assertThat(SupersessionResolver.decisionsAfter(" 결정 5~8, 기타")).containsExactly(5, 6, 7, 8);
  }

  @Test
  void phase1ExclusionIsNotOverridden() {
    var rel = List.of(new Relation("adr/0026", "adr/0011", java.util.Set.of(3), true));
    assertThat(SupersessionResolver.apply(Lifecycle.EXCLUDED_FROM_PHASE1, 3, rel).lifecycle())
        .isEqualTo(Lifecycle.EXCLUDED_FROM_PHASE1);
  }
}
