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
 * 검색 <b>기준선</b> 평가: 실제 저장소 문서(manifest 전체)를 로컬 해시 임베딩으로 색인해 질문별 상위 5 근거의 recall@5·MRR 을 잰다. 지표는 두
 * 가지다.
 *
 * <ul>
 *   <li><b>문서 경로 기준</b>({@code expected}, 처음부터 쓰던 것): 상위 5 안에 정답 문서의 청크가 있으면 적중 → {@code
 *       build/reports/eval/retrieval-baseline.md}.
 *   <li><b>절 기준</b>({@code sections}, 2026-11-05 추가): 정답 문서의 <i>정답 절</i> 청크여야 적중 → {@code
 *       build/reports/eval/retrieval-section.md}. 정답 절은 검색 결과를 보지 않고 원문을 읽어 정했다(questions.yml 머리
 *       주석).
 * </ul>
 *
 * <p>이 수치는 어휘 겹침 기준선이다. 의미 검색·생성 답변 품질·환각·주입 방어는 실제 모델 평가 전까지 미검증(ADR-0036).
 */
class RetrievalEvalIT extends AssistantITBase {

  static final Path REPO = Path.of("../..").toAbsolutePath().normalize();

  @Autowired Retriever retriever;
  @Autowired SnapshotStore snapshots;

  record Q(String id, String q, List<String> expected, List<List<String>> sections) {}

  record Row(
      Q q,
      int rank,
      List<String> top,
      int thresholdPassed,
      List<RetrievedChunk> chunks,
      int sectionRank) {}

  @Test
  void baselineRecallAndMrr() throws IOException {
    long t0 = System.nanoTime();
    var result = ingestor.ingest(REPO, KnowledgeJobs.loadManifest());
    ingestSeconds = (System.nanoTime() - t0) / 1e9;
    var snapshot = snapshots.find(result.snapshotId()).orElseThrow();
    List<Row> rows = new ArrayList<>();
    long q0 = System.nanoTime();
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
      int sectionRank = 0;
      for (int i = 0; i < r.ranked().size(); i++) {
        if (sectionHit(q, r.ranked().get(i).path(), r.ranked().get(i).section())) {
          sectionRank = i + 1;
          break;
        }
      }
      rows.add(new Row(q, rank, top, top.size(), r.ranked(), sectionRank));
    }
    queryMillis = (System.nanoTime() - q0) / 1e6 / Math.max(1, rows.size());
    label = embedding.spec().describe();
    // 정답 절이 색인에 실제로 있는지(오타·제목 변경으로 영원히 못 맞히는 정답을 막는다)
    List<String[]> indexed =
        jdbc.query(
            "SELECT path, section_path FROM doc_chunk WHERE snapshot_id = ?",
            (rs, i) -> new String[] {rs.getString(1), rs.getString(2)},
            result.snapshotId());
    for (Q q : questions()) {
      for (List<String> sec : q.sections()) {
        assertThat(
                indexed.stream()
                    .anyMatch(c -> c[0].startsWith(sec.get(0)) && segmentStarts(c[1], sec.get(1))))
            .as("%s 정답 절이 색인에 있다: %s", q.id(), sec)
            .isTrue();
      }
    }
    double recall = rows.stream().filter(x -> x.rank() > 0).count() / (double) rows.size();
    double mrr =
        rows.stream().mapToDouble(x -> x.rank() == 0 ? 0 : 1.0 / x.rank()).average().orElse(0);
    long noGrounding = rows.stream().filter(x -> x.thresholdPassed() == 0).count();
    write(result.chunkCount(), result.fileCount(), rows, recall, mrr, noGrounding);
    double sRecall = rows.stream().filter(x -> x.sectionRank() > 0).count() / (double) rows.size();
    double sMrr =
        rows.stream()
            .mapToDouble(x -> x.sectionRank() == 0 ? 0 : 1.0 / x.sectionRank())
            .average()
            .orElse(0);
    writeSection(rows, sRecall, sMrr, recall, mrr);
    assertThat(rows).hasSizeGreaterThanOrEqualTo(20);
    assertThat(recall).as("기준선 recall@5 — 보고용, 품질 보증 아님").isGreaterThan(0.0);
  }

  /** 보고서 제목·폴더: 해시 기준선이면 그대로(build/reports/eval/), 비교 평가면 모델별 하위 폴더. */
  static String label = "local-hash-ngram@1/384";

  static double ingestSeconds;
  static double queryMillis;

  static Path report(String file) {
    String m = System.getProperty("pmart.eval.ollama-model", "");
    return m.isBlank()
        ? Path.of("build/reports/eval", file)
        : Path.of("build/reports/eval", m.replaceAll("[^A-Za-z0-9.-]", "_"), file);
  }

  static String timing() {
    return String.format(
        Locale.ROOT, "- 색인 %.1f초 · 질문당 검색 %.0f ms(임베딩 포함)%n", ingestSeconds, queryMillis);
  }

  @SuppressWarnings("unchecked")
  static List<Q> questions() throws IOException {
    try (InputStream in = new ClassPathResource("eval/questions.yml").getInputStream()) {
      Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      List<Q> out = new ArrayList<>();
      for (Object o : (List<Object>) root.get("questions")) {
        Map<String, Object> m = (Map<String, Object>) o;
        out.add(
            new Q(
                (String) m.get("id"),
                (String) m.get("q"),
                (List<String>) m.get("expected"),
                (List<List<String>>) m.getOrDefault("sections", List.of())));
      }
      return out;
    }
  }

  /** 청크가 질문의 정답 절인가: 경로 앞부분 일치 + section 의 한 단계가 정답 절 제목으로 시작. */
  static boolean sectionHit(Q q, String path, String section) {
    return q.sections().stream()
        .anyMatch(s -> path.startsWith(s.get(0)) && segmentStarts(section, s.get(1)));
  }

  static boolean segmentStarts(String section, String heading) {
    if (section == null) {
      return false;
    }
    for (String seg : section.split(" > ")) {
      if (seg.startsWith(heading)) {
        return true;
      }
    }
    return false;
  }

  private static void writeSection(
      List<Row> rows, double recall, double mrr, double docRecall, double docMrr)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("# 검색 평가 — 절 기준 (" + label + ")\n\n")
        .append("> 어휘 겹침 기준선 — 의미 검색·생성 품질 미검증(ADR-0036).\n")
        .append(
            "> **절 기준**: 상위 5 안에 정답 절(questions.yml `sections` — 원문을 읽어 정함, 검색 결과로 넓히지 않음)의 청크가 있어야"
                + " 적중.\n\n")
        .append(
            String.format(
                Locale.ROOT,
                "- 질문 %d · 절 기준 recall@5 **%.2f** · MRR **%.3f** (같은 실행의 문서 경로 기준: recall@5 %.2f · MRR"
                    + " %.3f)%n%n",
                rows.size(),
                recall,
                mrr,
                docRecall,
                docMrr))
        .append("| id | 문서 기준 순위 | 절 기준 순위 | 정답 절 |\n|---|---|---|---|\n");
    for (Row r : rows) {
      sb.append("| ")
          .append(r.q().id())
          .append(" | ")
          .append(r.rank() == 0 ? "—" : r.rank())
          .append(" | ")
          .append(r.sectionRank() == 0 ? "—" : r.sectionRank())
          .append(" | ")
          .append(
              String.join(
                  "<br>",
                  r.q().sections().stream()
                      .map(s -> s.get(0).replace("docs/", "") + " § " + s.get(1))
                      .toList()))
          .append(" |\n");
    }
    sb.append("\n## 문서는 맞혔지만 절은 못 맞힌 질문 — 상위 5 근거\n");
    for (Row r : rows) {
      if (r.rank() == 0 || r.sectionRank() != 0) {
        continue;
      }
      sb.append("\n### ").append(r.q().id()).append(" — ").append(r.q().q()).append("\n");
      int i = 1;
      for (RetrievedChunk c : r.chunks()) {
        sb.append(
            String.format(
                Locale.ROOT,
                "%d. %.3f %s — %s%n",
                i++,
                c.similarity(),
                c.path(),
                c.section() == null ? "(절 없음)" : c.section()));
      }
    }
    Path out = report("retrieval-section.md");
    Files.createDirectories(out.getParent());
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
  }

  private static void write(
      int chunks, int files, List<Row> rows, double recall, double mrr, long noGrounding)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("# 검색 평가 — 문서 경로 기준 (" + label + ")\n\n")
        .append("> 어휘 겹침 기준선 — 의미 검색·생성 품질 미검증(ADR-0036).\n")
        .append(
            "> **문서 경로 기준**: 상위 5 안에 정답 문서(경로 앞부분 일치)의 청크가 하나라도 있으면 적중. 그 청크가 답을 담은 절인지는 보지"
                + " 않는다 — 절 기준은 retrieval-section.md.\n\n")
        .append(String.format(Locale.ROOT, "- 색인: 파일 %d · 청크 %d%n", files, chunks))
        .append(timing())
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
    Path out = report("retrieval-baseline.md");
    Files.createDirectories(out.getParent());
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
  }
}
