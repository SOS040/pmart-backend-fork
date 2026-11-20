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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 실제 저장소 문서(manifest 전체)로 색인 범위·청크 회귀를 고정한다. DB 없이 — 임베딩 캐시만 모의.
 *
 * <p>2026-11-20 사용자: 운영자용 RAG 는 {@code docs/rag/rag-docs} 아래 모든 문서(README 제외)만 참고한다. 옛 색인 문서 52개(업무
 * 원문·ADR 등)는 목록에서 뺐다. 지위·생애 판정 규칙 자체는 MarkdownChunker·SupersessionResolver 단위 시험이 지킨다.
 */
class RealDocumentsIngestTest {

  static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
  static final Path RAG_DOCS = REPO.resolve("docs/rag/rag-docs");
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

  /** rag-docs 의 README 밖 .md 가 모두 목록에 있고, 목록에는 그것만 있다(새 문서를 넣고 manifest 를 빠뜨리면 실패). */
  @Test
  void manifestListsExactlyRagDocsWithoutReadme() throws IOException {
    Set<String> files;
    try (Stream<Path> s = Files.walk(RAG_DOCS)) {
      files =
          s.filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".md"))
              .filter(p -> !p.getFileName().toString().equals("README.md"))
              .map(p -> REPO.relativize(p).toString().replace('\\', '/'))
              .collect(Collectors.toSet());
    }
    Set<String> listed = manifest.documents().stream().map(Doc::path).collect(Collectors.toSet());
    assertThat(listed).as("manifest 경로가 중복되지 않는다").hasSize(manifest.documents().size());
    assertThat(listed).containsExactlyInAnyOrderElementsOf(files);
    assertThat(manifest.documents())
        .allSatisfy(
            d -> {
              assertThat(d.path()).startsWith("docs/rag/rag-docs/");
              assertThat(d.domain())
                  .as("domain = rag-docs 하위 폴더")
                  .isEqualTo(Path.of(d.path()).getParent().getFileName().toString());
            });
  }

  /** 고객용 문서(customer_app)도 운영자용 RAG 범위에 들어 있다(사용자 2026-11-20 5-5). */
  @Test
  void operatorScopeIncludesCustomerAppDocs() {
    assertThat(manifest.documents())
        .anyMatch(d -> d.path().startsWith("docs/rag/rag-docs/customer_app/"));
  }

  /** 새 문서에는 쓰지 않기로 한 표현(사용자 2026-11-20 5-3)이 없다 — 지위 태그도 없으므로 모든 청크가 확정·현행이다. */
  @Test
  void documentsHaveNoBannedWordsAndChunksAreConfirmedActive() throws IOException {
    for (Doc d : manifest.documents()) {
      String text = Files.readString(REPO.resolve(d.path()), StandardCharsets.UTF_8);
      assertThat(text).as(d.path()).doesNotContain("가정", "설계안", "확정 정책");
    }
    assertThat(rows).isNotEmpty();
    assertThat(rows)
        .allSatisfy(
            r -> {
              assertThat(r.authority()).as(r.chunkId()).isEqualTo("CONFIRMED");
              assertThat(r.lifecycleStatus()).as(r.chunkId()).isEqualTo("ACTIVE");
            });
  }

  @Test
  void everyDocumentYieldsChunksAndIdsAreUnique() {
    Set<String> withChunks = rows.stream().map(ChunkRow::path).collect(Collectors.toSet());
    assertThat(withChunks)
        .containsExactlyInAnyOrderElementsOf(
            manifest.documents().stream().map(Doc::path).collect(Collectors.toSet()));
    assertThat(rows).extracting(ChunkRow::chunkId).doesNotHaveDuplicates();
    assertThat(rows).allSatisfy(r -> assertThat(r.embedding()).hasSize(384));
  }
}
