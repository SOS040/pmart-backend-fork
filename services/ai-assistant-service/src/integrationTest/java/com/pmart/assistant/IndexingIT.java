package com.pmart.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.assistant.application.KnowledgeJobs;
import com.pmart.assistant.infrastructure.AdvisoryLocks;
import com.pmart.assistant.infrastructure.AnswerStore;
import com.pmart.assistant.infrastructure.SnapshotStore;
import com.pmart.assistant.infrastructure.ingest.KnowledgeIngestor;
import com.pmart.assistant.infrastructure.ingest.KnowledgeIngestor.IngestFailedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 색인 작업: 멱등·변경 반영·실패 시 ACTIVE 유지·동시 색인 차단·차원 검증·색인 중 변경·스냅숏 고정 vs 정리. */
class IndexingIT extends AssistantITBase {

  @Autowired SnapshotStore snapshots;
  @Autowired AnswerStore answers;
  @Autowired KnowledgeJobs jobs;
  @Autowired DataSource dataSource;

  List<String> chunkIds(String snapshotId) {
    return jdbc.queryForList(
        "SELECT chunk_id FROM doc_chunk WHERE snapshot_id = ? ORDER BY chunk_id",
        String.class,
        snapshotId);
  }

  @Test
  void reindexIsIdempotentAndRetiresPreviousWithoutDeleting() {
    var first = ingest();
    var second = ingest();
    assertThat(chunkIds(second.snapshotId())).isEqualTo(chunkIds(first.snapshotId()));
    assertThat(snapshots.find(first.snapshotId()).orElseThrow().state()).isEqualTo("RETIRED");
    assertThat(activeSnapshot()).isEqualTo(second.snapshotId());
    // 원본 파일 해시 기록(작업 트리가 git 이 아니어도 입력을 식별할 수 있다)
    assertThat(snapshots.sourceFiles(second.snapshotId()))
        .hasSize(FIXTURE_FILES.size())
        .allSatisfy(f -> assertThat(f.sha256()).hasSize(64));
    assertThat(snapshots.find(second.snapshotId()).orElseThrow().sourceDirty()).isTrue();
    // 임베딩 캐시: 같은 모델·전처리·본문이면 재사용(행 수가 늘지 않는다)
    Integer cached = jdbc.queryForObject("SELECT count(*) FROM embedding_cache", Integer.class);
    ingest();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM embedding_cache", Integer.class))
        .isEqualTo(cached);
  }

  @Test
  void changedDocumentIsReflected() throws IOException {
    ingest();
    Files.writeString(
        repo.resolve("docs/business/policy.md"),
        Files.readString(repo.resolve("docs/business/policy.md")).replace("100원", "150원"));
    var next = ingest();
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM doc_chunk WHERE snapshot_id = ? AND content LIKE '%150원%'",
            Integer.class, next.snapshotId());
    assertThat(n).isEqualTo(1);
  }

  @Test
  void deletedManifestFileFailsAndKeepsActive() throws IOException {
    var ok = ingest();
    Files.delete(repo.resolve("docs/adr/0003-category.md"));
    assertThatThrownBy(this::ingest)
        .isInstanceOf(IngestFailedException.class)
        .hasMessageContaining("manifest 경로 없음");
    assertThat(activeSnapshot()).isEqualTo(ok.snapshotId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM index_snapshot WHERE state='FAILED'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void fileChangedDuringIngestFailsAndKeepsActive() {
    var ok = ingest();
    jdbc.update("DELETE FROM embedding_cache"); // 캐시가 있으면 임베딩을 부르지 않으므로 비운다
    embedding.onNextEmbed.set(
        () -> {
          try {
            Files.writeString(
                repo.resolve("docs/secret/hq.md"), "\n수정", java.nio.file.StandardOpenOption.APPEND);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
    assertThatThrownBy(this::ingest)
        .isInstanceOf(IngestFailedException.class)
        .hasMessageContaining("색인 도중 파일이 바뀌었습니다");
    assertThat(activeSnapshot()).isEqualTo(ok.snapshotId());
  }

  @Test
  void dimensionMismatchFails() {
    jdbc.update("DELETE FROM embedding_cache");
    embedding.wrongDimension.set(true);
    assertThatThrownBy(this::ingest).isInstanceOf(IngestFailedException.class);
    assertThat(activeSnapshot()).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT failure_reason FROM index_snapshot WHERE state='FAILED'", String.class))
        .contains("차원 불일치");
  }

  @Test
  void concurrentIngestIsBlockedButQuestionsKeepUsingActive() throws Exception {
    var ok = ingest();
    try (Connection other = dataSource.getConnection();
        var ps = other.prepareStatement("SELECT pg_advisory_lock(?)")) {
      ps.setLong(1, AdvisoryLocks.INGEST);
      ps.executeQuery().close();
      try {
        assertThatThrownBy(this::ingest)
            .isInstanceOf(IngestFailedException.class)
            .hasMessageContaining("INGEST");
        // 색인 잠금은 질의와 무관 — 재색인 중에도 기존 ACTIVE 로 답한다
        Http r = ask(operator("op-1"), java.util.UUID.randomUUID().toString(), "봉투값은 얼마인가요?");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("indexSnapshotId")).isEqualTo(ok.snapshotId());
      } finally {
        try (var un = other.prepareStatement("SELECT pg_advisory_unlock(?)")) {
          un.setLong(1, AdvisoryLocks.INGEST);
          un.executeQuery().close();
        }
      }
    }
    assertThat(activeSnapshot()).isEqualTo(ok.snapshotId());
  }

  @Test
  void abandonedBuildingIsFailedByNextIngestAndThenPruned() {
    var first = ingest();
    // 색인 도중 프로세스가 죽은 것처럼 BUILDING 을 남긴다(FAILED 로 바꿀 기회가 없었다)
    jdbc.update(
        """
        INSERT INTO index_snapshot (snapshot_id, embedding_model, embedding_model_version, embedding_dim,
          preprocessing_hash, manifest_hash, source_dirty, state, created_at)
        SELECT 'SNAP-abandoned', embedding_model, embedding_model_version, embedding_dim,
          preprocessing_hash, manifest_hash, source_dirty, 'BUILDING', ?
        FROM index_snapshot WHERE snapshot_id = ?
        """,
        Timestamp.from(Instant.now().minus(Duration.ofDays(1))),
        first.snapshotId());
    assertThat(jobs.pruneSnapshots()).as("BUILDING 은 정리 대상이 아니다").isZero();

    var second = ingest();
    assertThat(activeSnapshot()).isEqualTo(second.snapshotId());
    var abandoned = snapshots.find("SNAP-abandoned").orElseThrow();
    assertThat(abandoned.state()).isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT failure_reason FROM index_snapshot WHERE snapshot_id = 'SNAP-abandoned'",
                String.class))
        .isEqualTo(KnowledgeIngestor.ABANDONED_REASON);
    // 방금 RETIRED 된 first 는 유예 안이라 남고, 하루 전에 버려진 것만 지워진다
    assertThat(jobs.pruneSnapshots()).isEqualTo(1);
    assertThat(snapshots.find("SNAP-abandoned")).isEmpty();
    assertThat(snapshots.find(first.snapshotId())).isPresent();
  }

  @Test
  void pruneSkipsSnapshotPinnedByProcessingRequest() {
    var old = ingest();
    Instant now = Instant.now();
    answers.insertClaim(
        "ANS-pin",
        "backoffice-web",
        "op-1",
        "k-pin",
        "h".repeat(64),
        now.plusSeconds(120),
        null,
        now);
    assertThat(answers.pinActiveSnapshot("ANS-pin", 1, snapshots))
        .isInstanceOf(AnswerStore.PinResult.Pinned.class);
    ingest(); // old → RETIRED
    jdbc.update(
        "UPDATE index_snapshot SET retired_at = ? WHERE snapshot_id = ?",
        Timestamp.from(now.minus(Duration.ofDays(1))),
        old.snapshotId());
    assertThat(jobs.pruneSnapshots()).isZero();
    assertThat(snapshots.find(old.snapshotId())).isPresent();
    // 요청이 끝나면 지울 수 있다
    answers.fail("ANS-pin", 1, "DEPENDENCY_UNAVAILABLE");
    assertThat(jobs.pruneSnapshots()).isEqualTo(1);
  }

  @Test
  void pruneWaitsForInFlightPinAndThenSeesIt() throws Exception {
    var old = ingest();
    Instant now = Instant.now();
    answers.insertClaim(
        "ANS-race",
        "backoffice-web",
        "op-1",
        "k-race",
        "h".repeat(64),
        now.plusSeconds(120),
        null,
        now);
    jdbc.update(
        "UPDATE index_snapshot SET state='RETIRED', retired_at = ? WHERE snapshot_id = ?",
        Timestamp.from(now.minus(Duration.ofDays(1))),
        old.snapshotId());
    try (Connection pinTx = dataSource.getConnection()) {
      pinTx.setAutoCommit(false);
      try (var lock = pinTx.prepareStatement("SELECT pg_advisory_xact_lock_shared(?)")) {
        lock.setLong(1, AdvisoryLocks.SNAPSHOT);
        lock.executeQuery().close();
      }
      // 고정 TX 가 공유 잠금을 쥔 동안 정리는 기다린다
      CompletableFuture<Integer> prune = CompletableFuture.supplyAsync(jobs::pruneSnapshots);
      Thread.sleep(500);
      assertThat(prune).isNotDone();
      try (var pin =
          pinTx.prepareStatement(
              "UPDATE policy_answer SET snapshot_id = ? WHERE answer_id = 'ANS-race'")) {
        pin.setString(1, old.snapshotId());
        pin.executeUpdate();
      }
      pinTx.commit();
      assertThat(prune.get(10, TimeUnit.SECONDS)).isZero(); // 커밋된 고정을 보고 지우지 않는다
    }
    assertThat(snapshots.find(old.snapshotId())).isPresent();
  }
}
