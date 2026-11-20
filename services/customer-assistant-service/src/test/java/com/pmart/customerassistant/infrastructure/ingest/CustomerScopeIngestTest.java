package com.pmart.customerassistant.infrastructure.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pmart.customerassistant.infrastructure.SnapshotStore;
import com.pmart.customerassistant.infrastructure.SnapshotStore.ChunkRow;
import com.pmart.customerassistant.infrastructure.embedding.LocalHashEmbeddingModel;
import java.io.IOException;
import java.io.InputStream;
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
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 고객용 RAG 의 답변 근거 범위(사용자 2026-11-20): {@code docs/rag/rag-docs/customer_app} 문서만(README 제외). FAQ 목록
 * 전용 문서(domain customer-faq)는 청크가 되지 않는다.
 */
class CustomerScopeIngestTest {

  static final Path REPO = Path.of("../..").toAbsolutePath().normalize();

  @Test
  void answerChunksComeOnlyFromCustomerAppDocs() throws IOException {
    KnowledgeManifest manifest;
    try (InputStream in = new ClassPathResource("knowledge/manifest.yml").getInputStream()) {
      manifest = KnowledgeManifest.load(in);
    }
    Map<String, byte[]> bytes = new LinkedHashMap<>();
    for (var d : manifest.documents()) {
      bytes.put(d.path(), Files.readAllBytes(REPO.resolve(d.path())));
    }
    SnapshotStore store = mock(SnapshotStore.class);
    when(store.cachedEmbedding(any(), any())).thenReturn(Optional.empty());
    KnowledgeIngestor ingestor =
        new KnowledgeIngestor(
            null, store, new LocalHashEmbeddingModel(384), null, Clock.systemUTC(), 1500);
    List<ChunkRow> rows =
        ingestor.buildChunks(manifest, bytes, new LocalHashEmbeddingModel(384).spec());

    Set<String> customerApp;
    try (Stream<Path> s = Files.list(REPO.resolve("docs/rag/rag-docs/customer_app"))) {
      customerApp =
          s.map(p -> p.getFileName().toString())
              .filter(n -> n.endsWith(".md") && !n.equals("README.md"))
              .map(n -> "docs/rag/rag-docs/customer_app/" + n)
              .collect(Collectors.toSet());
    }
    assertThat(rows.stream().map(ChunkRow::path).collect(Collectors.toSet()))
        .containsExactlyInAnyOrderElementsOf(customerApp);
    assertThat(manifest.documents())
        .filteredOn(d -> !FaqExtractor.FAQ_DOMAIN.equals(d.domain()))
        .extracting(KnowledgeManifest.Doc::path)
        .containsExactlyInAnyOrderElementsOf(customerApp);
  }
}
