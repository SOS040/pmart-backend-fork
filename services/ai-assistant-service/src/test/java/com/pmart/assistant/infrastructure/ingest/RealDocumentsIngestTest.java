package com.pmart.assistant.infrastructure.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pmart.assistant.application.KnowledgeJobs;
import com.pmart.assistant.infrastructure.SnapshotStore;
import com.pmart.assistant.infrastructure.SnapshotStore.ChunkRow;
import com.pmart.assistant.infrastructure.embedding.LocalHashEmbeddingModel;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest.Doc;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 실제 저장소 문서(manifest 전체)로 청크·지위·생애 판정 회귀를 고정한다. DB 없이 — 임베딩 캐시만 모의.
 *
 * <p>검토 1 회귀: 제안 ADR 이 승인 결정을 SUPERSEDED 로 만들지 않는다(0025→0019, 0015→Commerce), 승인 ADR 은 명시 결정만
 * 대체(0026→0011).
 */
class RealDocumentsIngestTest {

  static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
  static KnowledgeManifest manifest;
  static List<ChunkRow> rows;

  @BeforeAll
  static void build() throws IOException {
    manifest = KnowledgeJobs.loadManifest();
    SnapshotStore store = mock(SnapshotStore.class);
    when(store.cachedEmbedding(any(), any())).thenReturn(Optional.empty());
    KnowledgeIngestor ingestor =
        new KnowledgeIngestor(
            null, store, new LocalHashEmbeddingModel(384), null, Clock.systemUTC(), 1500);
    Map<String, byte[]> bytes = new LinkedHashMap<>();
    for (Doc d : manifest.documents()) {
      bytes.put(d.path(), Files.readAllBytes(REPO.resolve(d.path())));
    }
    rows = ingestor.buildChunks(manifest, bytes, new LocalHashEmbeddingModel(384).spec());
  }

  @Test
  void manifestListsExistingFilesExplicitly() {
    assertThat(manifest.documents())
        .allSatisfy(d -> assertThat(REPO.resolve(d.path())).isRegularFile());
    long adrFiles;
    try (var s = Files.list(REPO.resolve("docs/adr"))) {
      adrFiles = s.filter(p -> p.getFileName().toString().matches("^\\d{4}-.*\\.md$")).count();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    assertThat(manifest.documents().stream().filter(d -> d.kind() == Kind.ADR).count())
        .isEqualTo(adrFiles);
    assertThat(manifest.documents())
        .noneMatch(d -> d.path().contains("worklog") || d.path().contains("handoff"));
  }

  @Test
  void approvedAdr0026SupersedesOnlyDecisions3And5Of0011() {
    var adr11 = rows.stream().filter(r -> r.path().startsWith("docs/adr/0011-")).toList();
    assertThat(adr11)
        .filteredOn(r -> r.lifecycleStatus().equals("SUPERSEDED"))
        .extracting(ChunkRow::sectionPath)
        .containsExactlyInAnyOrder("결정 > 3", "결정 > 5");
    assertThat(adr11)
        .filteredOn(r -> "결정 > 1".equals(r.sectionPath()))
        .singleElement()
        .satisfies(r -> assertThat(r.lifecycleStatus()).isEqualTo("ACTIVE"));
    assertThat(adr11)
        .filteredOn(r -> r.lifecycleStatus().equals("SUPERSEDED"))
        .allSatisfy(r -> assertThat(r.supersededBy()).startsWith("docs/adr/0026-"));
  }

  @Test
  void proposedAdr0025OnlyMarksProposalOn0019() {
    var adr19 = rows.stream().filter(r -> r.path().startsWith("docs/adr/0019-")).toList();
    assertThat(adr19).noneMatch(r -> r.lifecycleStatus().equals("SUPERSEDED"));
    assertThat(adr19)
        .filteredOn(r -> "결정 > 1".equals(r.sectionPath()))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.lifecycleStatus()).isEqualTo("SUPERSESSION_PROPOSED");
              assertThat(r.proposedSupersededBy()).anyMatch(p -> p.startsWith("docs/adr/0025-"));
              assertThat(r.authority()).isEqualTo("PROPOSAL"); // 0019 자체가 제안 — 지위 유지
            });
  }

  @Test
  void commerceConfirmedCategoryPolicyKeepsAuthorityWithProposalMark() {
    var chunk =
        rows.stream()
            .filter(r -> r.path().equals("docs/business/Commerce-business-logic.md"))
            .filter(r -> r.content().contains("고객 분류는 1·2단계"))
            .findFirst()
            .orElseThrow();
    assertThat(chunk.authority()).isEqualTo("CONFIRMED");
    assertThat(chunk.lifecycleStatus()).isEqualTo("SUPERSESSION_PROPOSED");
    assertThat(chunk.proposedSupersededBy())
        .containsExactly("docs/adr/0015-category-tree-follows-reference-data.md");
  }

  /**
   * ADR-0049(승인)가 업무 원문 OMS 8.2 결품 처리를 바꿨다 — 그 문단만 SUPERSEDED(manifest override, 2026-11-09 A13).
   */
  @Test
  void omsShortagePreselectionIsSupersededByAdr0049Only() {
    var oms =
        rows.stream().filter(r -> r.path().equals("docs/business/OMS-business-logic.md")).toList();
    assertThat(oms)
        .filteredOn(r -> r.content().contains("주문 당시 고객은 `품절 상품만 취소` 또는 `전체 취소`를 선택한다"))
        .isNotEmpty()
        .allSatisfy(
            r -> {
              assertThat(r.lifecycleStatus()).isEqualTo("SUPERSEDED");
              assertThat(r.supersededBy())
                  .isEqualTo("docs/adr/0049-shortage-always-partial-cancel.md");
            });
    // 이웃 절(8.3 취소와 인계 경합)은 그대로
    assertThat(oms)
        .filteredOn(r -> r.sectionPath() != null && r.sectionPath().contains("8.3 취소와 인계 경합"))
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.lifecycleStatus()).isEqualTo("ACTIVE"));
  }

  @Test
  void commerceAppendixCIsExcludedFromPhase1() {
    assertThat(rows)
        .filteredOn(r -> r.sectionPath() != null && r.sectionPath().startsWith("부록 C."))
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.lifecycleStatus()).isEqualTo("EXCLUDED_FROM_PHASE1"));
  }

  @Test
  void implementationRecordDateIsSeparateFromAuthority() {
    // 절마다 자기 "구현 상태 (날짜…)" — 1.4 는 2026-10-31, 2.0.x(슬라이스 6, 2026-11-07 추가)는 2026-11-07
    var recorded =
        rows.stream()
            .filter(r -> r.path().endsWith("order-checkout-saga.md") && r.implRecordDate() != null)
            .toList();
    assertThat(recorded).isNotEmpty();
    assertThat(recorded)
        .filteredOn(r -> r.sectionPath() != null && r.sectionPath().contains("1.4 구현 상태"))
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.implRecordDate()).isEqualTo("2026-10-31"));
    assertThat(recorded)
        .filteredOn(r -> r.sectionPath() != null && r.sectionPath().contains("2.0.1 구현 상태"))
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.implRecordDate()).isEqualTo("2026-11-07"));
    // 지위는 날짜로 바뀌지 않는다
    assertThat(recorded)
        .extracting(ChunkRow::implRecordDate)
        .containsOnly("2026-10-31", "2026-11-07");
  }

  @Test
  void adrAuthorityComesFromStatusLine() {
    assertThat(rows)
        .filteredOn(r -> r.path().startsWith("docs/adr/0026-"))
        .allSatisfy(r -> assertThat(r.authority()).isEqualTo("CONFIRMED"));
    assertThat(rows)
        .filteredOn(r -> r.path().startsWith("docs/adr/0025-"))
        .allSatisfy(r -> assertThat(r.authority()).isEqualTo("PROPOSAL"));
  }

  @Test
  void chunkIdsAreUniqueAndVersionsAreNotInvented() {
    assertThat(rows).extracting(ChunkRow::chunkId).doesNotHaveDuplicates();
    assertThat(rows)
        .filteredOn(r -> r.path().equals("docs/business/glossary.md"))
        .allSatisfy(r -> assertThat(r.documentVersion()).isNull());
    assertThat(rows)
        .filteredOn(r -> r.path().equals("docs/business/WMS-business-logic.md"))
        .allSatisfy(r -> assertThat(r.documentVersion()).isEqualTo("8.1"));
    assertThat(rows).allSatisfy(r -> assertThat(r.embedding()).hasSize(384));
  }
}
