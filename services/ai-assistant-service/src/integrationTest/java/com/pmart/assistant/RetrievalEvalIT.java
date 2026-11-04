package com.pmart.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.assistant.application.KnowledgeJobs;
import com.pmart.assistant.application.Retriever;
import com.pmart.assistant.domain.QuestionClassifier;
import com.pmart.assistant.domain.RetrievedChunk;
import com.pmart.assistant.infrastructure.SnapshotStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 검색 <b>기준선</b> 평가: 실제 저장소 문서(manifest 전체)를 로컬 해시 임베딩으로 색인해 질문별 상위 5 근거의 recall@5·MRR 을 잰다. 결과는
 * {@code build/reports/eval/retrieval-baseline.md} 에 쓴다.
 *
 * <p>이 수치는 어휘 겹침 기준선이다. 의미 검색·생성 답변 품질·환각·주입 방어는 실제 모델 평가 전까지 미검증(ADR-0036).
 */
class RetrievalEvalIT extends AssistantITBase {

  static final Path REPO = Path.of("../..").toAbsolutePath().normalize();

  @Autowired Retriever retriever;
  @Autowired SnapshotStore snapshots;

  record Q(String id, String q, List<String> expected) {}

  record Row(Q q, int rank, List<String> top, int thresholdPassed, List<RetrievedChunk> chunks) {}

  @Test
  void baselineRecallAndMrr() throws IOException {
    var result = ingestor.ingest(REPO, KnowledgeJobs.loadManifest());
    var snapshot = snapshots.find(result.snapshotId()).orElseThrow();
    List<Row> rows = new ArrayList<>();
    for (Q q : questions()) {
      var cls = QuestionClassifier.classify(q.q());
      Retriever.Retrieval r =
          retriever.retrieve(snapshot, q.q(), cls.history(), Set.of("operator"));
      List<String> top = r.ranked().stream().map(RetrievedChunk::path).toList();
      int rank = 0;
      for (int i = 0; i < top.size(); i++) {
        String p = top.get(i);
        if (q.expected().stream().anyMatch(p::startsWith)) {
          rank = i + 1;
          break;
        }
      }
      rows.add(new Row(q, rank, top, top.size(), r.ranked()));
    }
    double recall = rows.stream().filter(x -> x.rank() > 0).count() / (double) rows.size();
    double mrr =
        rows.stream().mapToDouble(x -> x.rank() == 0 ? 0 : 1.0 / x.rank()).average().orElse(0);
    long noGrounding = rows.stream().filter(x -> x.thresholdPassed() == 0).count();
    write(result.chunkCount(), result.fileCount(), rows, recall, mrr, noGrounding);
    assertThat(rows).hasSizeGreaterThanOrEqualTo(20);
    assertThat(recall).as("기준선 recall@5 — 보고용, 품질 보증 아님").isGreaterThan(0.0);
  }

  @SuppressWarnings("unchecked")
  static List<Q> questions() throws IOException {
    try (InputStream in = new ClassPathResource("eval/questions.yml").getInputStream()) {
      Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      List<Q> out = new ArrayList<>();
      for (Object o : (List<Object>) root.get("questions")) {
        Map<String, Object> m = (Map<String, Object>) o;
        out.add(new Q((String) m.get("id"), (String) m.get("q"), (List<String>) m.get("expected")));
      }
      return out;
    }
  }

  private static void write(
      int chunks, int files, List<Row> rows, double recall, double mrr, long noGrounding)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("# 검색 기준선 평가 (local-hash-ngram@1/384)\n\n")
        .append("> 어휘 겹침 기준선 — 의미 검색·생성 품질 미검증(ADR-0036).\n\n")
        .append(String.format(Locale.ROOT, "- 색인: 파일 %d · 청크 %d%n", files, chunks))
        .append(
            String.format(
                Locale.ROOT,
                "- 질문 %d · recall@5 **%.2f** · MRR **%.3f** · 임계값 통과 0건(NO_GROUNDING) %d%n%n",
                rows.size(),
                recall,
                mrr,
                noGrounding))
        .append("| id | 적중 순위 | 상위 근거(앞 3) |\n|---|---|---|\n");
    for (Row r : rows) {
      sb.append("| ")
          .append(r.q().id())
          .append(" | ")
          .append(r.rank() == 0 ? "—" : r.rank())
          .append(" | ")
          .append(String.join("<br>", r.top().subList(0, Math.min(3, r.top().size()))))
          .append(" |\n");
    }
    sb.append("\n## 실패·저순위 사례 (적중 없음 또는 3위 밖) — 상위 5 근거\n");
    for (Row r : rows) {
      if (r.rank() != 0 && r.rank() <= 3) {
        continue;
      }
      sb.append("\n### ")
          .append(r.q().id())
          .append(r.rank() == 0 ? " (적중 없음)" : " (" + r.rank() + "위)")
          .append("\n- 질문: ")
          .append(r.q().q())
          .append("\n- 정답 근거: ")
          .append(String.join(", ", r.q().expected()))
          .append("\n");
      int i = 1;
      for (RetrievedChunk c : r.chunks()) {
        sb.append(
            String.format(
                Locale.ROOT,
                "%d. %.3f [%s] %s — %s%n",
                i++,
                c.similarity(),
                c.authority(),
                c.path(),
                c.section() == null ? "(절 없음)" : c.section()));
      }
    }
    Path out = Path.of("build/reports/eval/retrieval-baseline.md");
    Files.createDirectories(out.getParent());
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
  }
}
