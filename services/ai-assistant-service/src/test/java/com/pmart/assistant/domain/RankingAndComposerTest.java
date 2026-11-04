package com.pmart.assistant.domain;

import static com.pmart.assistant.domain.Chunks.chunk;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 순위(임계값 먼저, 지위·우선순위 가중은 그 뒤) + 발췌(청크를 자르지 않음, 개수로 제한, 머리말, 대체 제안 동반). */
class RankingAndComposerTest {

  private final RankingPolicy ranking =
      new RankingPolicy(0.3, RankingPolicy.defaultWeights(), 0.01);

  @Test
  void belowThresholdIsNotLiftedByAuthority() {
    var confirmedWeak = chunk("a", Authority.CONFIRMED, 0.29, "확정 정책");
    var proposalStrong = chunk("b", Authority.PROPOSAL, 0.31, "설계안");
    assertThat(ranking.rank(List.of(confirmedWeak, proposalStrong), 5))
        .extracting(RetrievedChunk::chunkId)
        .containsExactly("b");
  }

  @Test
  void authorityAndPrecedenceOrderCandidatesAboveThreshold() {
    var proposal =
        chunk("p", "docs/oms.md", Authority.PROPOSAL, Lifecycle.ACTIVE, List.of(), 3, 0.50, "x");
    var confirmed =
        chunk(
            "c",
            "docs/commerce.md",
            Authority.CONFIRMED,
            Lifecycle.ACTIVE,
            List.of(),
            2,
            0.48,
            "y");
    assertThat(ranking.rank(List.of(proposal, confirmed), 5))
        .extracting(RetrievedChunk::chunkId)
        .containsExactly("c", "p"); // 0.48+0.06+0.02 > 0.50+0.02+0.03
  }

  @Test
  void composerKeepsWholeChunksAndLimitsByCount() {
    String longA = "조건: 30,000원 이상이면 배달팁 0원. 예외: 예약배달은 3,000원. ".repeat(20);
    String longB = "B".repeat(3000);
    String shortC = "짧은 근거 C";
    var c =
        ExtractiveComposer.compose(
            List.of(
                chunk("a", Authority.CONFIRMED, 0.9, longA),
                chunk("b", Authority.CONFIRMED, 0.8, longB),
                chunk("c", Authority.CONFIRMED, 0.7, shortC)),
            List.of(),
            longA.length() + 100,
            5);
    assertThat(c.used()).extracting(RetrievedChunk::chunkId).containsExactly("a", "c");
    assertThat(c.text()).contains(longA.strip()).contains(shortC).doesNotContain("원문 계속");
  }

  @Test
  void firstChunkIsKeptWholeEvenIfOverLimit() {
    String big = "가".repeat(5000);
    var c =
        ExtractiveComposer.compose(
            List.of(chunk("a", Authority.CONFIRMED, 0.9, big)), List.of(), 100, 5);
    assertThat(c.text()).contains(big);
  }

  @Test
  void proposalOnlyGetsNoSettledPrefix() {
    var c =
        ExtractiveComposer.compose(
            List.of(
                chunk("a", Authority.PROPOSAL, 0.9, "설계안 내용"),
                chunk("b", Authority.UNDECIDED, 0.8, "미결정")),
            List.of(),
            4000,
            5);
    assertThat(c.text()).startsWith(ExtractiveComposer.NO_SETTLED_PREFIX);
    var settled =
        ExtractiveComposer.compose(
            List.of(chunk("a", Authority.CONFIRMED, 0.9, "확정")), List.of(), 4000, 5);
    assertThat(settled.text()).doesNotContain(ExtractiveComposer.NO_SETTLED_PREFIX);
  }

  @Test
  void supersessionProposedBringsProposingDocument() {
    String adr = "docs/adr/0015-category.md";
    var target =
        chunk(
            "t",
            "docs/commerce.md",
            Authority.CONFIRMED,
            Lifecycle.SUPERSESSION_PROPOSED,
            List.of(adr),
            2,
            0.9,
            "고객 분류는 1·2단계");
    var companion =
        chunk("adr", adr, Authority.PROPOSAL, Lifecycle.ACTIVE, List.of(), 5, 0.1, "참고 자료 구조를 따른다");
    var unrelated =
        chunk("u", "docs/other.md", Authority.PROPOSAL, Lifecycle.ACTIVE, List.of(), 5, 0.1, "무관");
    var c = ExtractiveComposer.compose(List.of(target), List.of(companion, unrelated), 4000, 5);
    assertThat(c.used()).extracting(RetrievedChunk::chunkId).containsExactly("t", "adr");
    assertThat(c.text()).contains("[확정]").contains("대체 제안 있음(미승인)").contains("[대체 제안 문서(미승인)]");
  }

  @Test
  void supersededAndImplementationLabels() {
    var superseded =
        new RetrievedChunk(
            "s",
            "docs/adr/0011.md",
            "docs/adr/0011.md",
            "결정 > 3",
            Authority.PROPOSAL,
            Lifecycle.SUPERSEDED,
            "docs/adr/0026.md",
            List.of(),
            5,
            "2026-10-31",
            "구현 상태 (2026-10-31)",
            null,
            null,
            "operator",
            "서비스 토큰 발급",
            0.9,
            0.9);
    assertThat(superseded.labels())
        .contains("[대체됨 → docs/adr/0026.md]")
        .contains("2026-10-31 기준 구현 기록 — 현재 상태는 이 기록만으로 확인할 수 없음");
  }
}
