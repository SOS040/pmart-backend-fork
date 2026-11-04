package com.pmart.assistant.infrastructure.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.assistant.domain.Authority;
import com.pmart.assistant.domain.Lifecycle;
import com.pmart.assistant.infrastructure.ingest.MarkdownChunker.ParsedChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {

  private final MarkdownChunker chunker = new MarkdownChunker(400);

  @Test
  void taggedParagraphsBecomeOwnChunksWithTheirAuthority() {
    String md =
        """
        # 문서

        ## 6.6 노출

        [확정 정책] 고객 분류는 1·2단계다.

        카테고리 활성 여부를 구분한다.

        `[설계안]` 품절은 마지막에 둔다.
        """;
    List<ParsedChunk> chunks = chunker.chunk(md, Authority.PROPOSAL, false);
    assertThat(chunks)
        .extracting(ParsedChunk::authority)
        .containsExactly(Authority.CONFIRMED, Authority.PROPOSAL, Authority.PROPOSAL);
    assertThat(chunks.get(0).content()).startsWith("[확정 정책]");
    assertThat(chunks).allMatch(c -> "6.6 노출".equals(c.section()));
  }

  @Test
  void tablesAndIntroducedListsStayWhole() {
    String md =
        """
        # 문서
        ## 규칙
        분할 조건은 다음과 같다:

        - 10,000g 초과면 분할
        - 정확히 10kg 은 미분할

        | 충돌 | 적용 |
        |---|---|
        | 분할 기준 | 10,000g 초과 |
        """;
    List<ParsedChunk> chunks = new MarkdownChunker(20).chunk(md, Authority.PROPOSAL, false);
    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0).content()).contains("다음과 같다:").contains("정확히 10kg 은 미분할");
    assertThat(chunks.get(1).content()).contains("| 충돌 | 적용 |").contains("| 분할 기준 |");
  }

  @Test
  void largeSectionsSplitOnlyAtBlockBoundaries() {
    String para = "가".repeat(300);
    String md = "# d\n## s\n" + para + "\n\n" + para + "\n\n" + "나".repeat(900) + "\n";
    List<ParsedChunk> chunks = chunker.chunk(md, Authority.PROPOSAL, false);
    assertThat(chunks).extracting(c -> c.content().length()).containsExactly(300, 300, 900);
  }

  @Test
  void adrDecisionsSplitPerNumberedItem() {
    String md =
        """
        # ADR-0011 서비스 신원 토큰
        - 상태: 제안

        ## 결정
        1. **RS256 서명키** 를 쓴다.
           - 세부
        2. **JWKS** 공개.
        3. **서비스 토큰 발급**.

        ## 대안
        없음
        """;
    List<ParsedChunk> chunks = chunker.chunk(md, Authority.PROPOSAL, true);
    assertThat(chunks)
        .filteredOn(c -> c.decisionNo() != null)
        .extracting(ParsedChunk::decisionNo, ParsedChunk::section)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1, "결정 > 1"),
            org.assertj.core.groups.Tuple.tuple(2, "결정 > 2"),
            org.assertj.core.groups.Tuple.tuple(3, "결정 > 3"));
    assertThat(
            chunks.stream()
                .filter(c -> Integer.valueOf(1).equals(c.decisionNo()))
                .findFirst()
                .orElseThrow()
                .content())
        .contains("세부");
  }

  @Test
  void implementationRecordAndPhase1ExclusionAreSeparateAxes() {
    String md =
        """
        # flow
        ## 1.4 구현 상태 (2026-10-31, 슬라이스 4·5)
        [확정 정책] 승인됨, 미구현 단계 있음.

        ## 부록 C. 후속 구현 참고 — 1차 제외
        ### C.1 쿠폰
        쿠폰 상세.
        """;
    List<ParsedChunk> chunks = chunker.chunk(md, Authority.PROPOSAL, false);
    ParsedChunk impl = chunks.get(0);
    assertThat(impl.authority()).isEqualTo(Authority.CONFIRMED);
    assertThat(impl.implRecordDate()).isEqualTo("2026-10-31");
    assertThat(impl.lifecycle()).isEqualTo(Lifecycle.ACTIVE);
    ParsedChunk excluded = chunks.get(1);
    assertThat(excluded.lifecycle()).isEqualTo(Lifecycle.EXCLUDED_FROM_PHASE1);
    assertThat(excluded.implRecordDate()).isNull();
    assertThat(excluded.section()).isEqualTo("부록 C. 후속 구현 참고 — 1차 제외 > C.1 쿠폰");
  }

  @Test
  void headerReadsOnlyWhatIsWritten() {
    var h = MarkdownChunker.header("# WMS\n\n문서 버전: v8.1 · 기준일: 2026-10-20 · 상태: 설계안\n");
    assertThat(h.version()).isEqualTo("8.1");
    assertThat(h.effectiveDate()).isEqualTo("2026-10-20");
    var none = MarkdownChunker.header("# 용어집\n\n> 설명\n");
    assertThat(none.version()).isNull();
    assertThat(none.effectiveDate()).isNull();
    var adr =
        MarkdownChunker.header(
            "# ADR-0026\n\n- 상태: 승인 (2026-10-31, 사용자 결정)\n- 대체: ADR-0011 의 결정 3(x)·5(y)\n");
    assertThat(adr.adrStatusLine()).contains("승인");
    assertThat(adr.effectiveDate()).isEqualTo("2026-10-31");
    assertThat(adr.supersessionLines()).hasSize(1);
  }
}
