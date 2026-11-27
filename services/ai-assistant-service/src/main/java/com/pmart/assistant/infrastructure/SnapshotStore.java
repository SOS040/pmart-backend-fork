package com.pmart.assistant.infrastructure;

import com.pmart.assistant.infrastructure.embedding.EmbeddingSpec;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** 색인 스냅숏·원본 파일·청크·임베딩 캐시 저장. 이 서비스 소유 표만 다룬다. */
@Repository
public class SnapshotStore {

  public record SnapshotRow(
      String snapshotId,
      String embeddingModel,
      String embeddingModelVersion,
      int embeddingDim,
      String preprocessingHash,
      String manifestHash,
      @Nullable String sourceRevision,
      boolean sourceDirty,
      String state,
      int chunkCount,
      @Nullable String failureReason,
      Instant createdAt,
      @Nullable Instant activatedAt,
      @Nullable Instant retiredAt) {}

  public record SourceFile(String path, String sha256, long bytes) {}

  public record ChunkRow(
      String chunkId,
      String documentId,
      String path,
      @Nullable String sectionPath,
      @Nullable String domainArea,
      String authority,
      String lifecycleStatus,
      @Nullable String supersededBy,
      List<String> proposedSupersededBy,
      int precedenceRank,
      @Nullable String implRecordDate,
      @Nullable String implNote,
      @Nullable String documentVersion,
      @Nullable String effectiveDate,
      @Nullable String stage,
      String accessScope,
      String content,
      String contentHash,
      float[] embedding) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public SnapshotStore(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  public void createBuilding(
      String snapshotId,
      EmbeddingSpec spec,
      String manifestHash,
      @Nullable String sourceRevision,
      boolean sourceDirty,
      Instant now) {
    jdbc.update(
        """
        INSERT INTO index_snapshot (snapshot_id, embedding_model, embedding_model_version, embedding_dim,
          preprocessing_hash, manifest_hash, source_revision, source_dirty, state, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'BUILDING', ?)
        """,
        snapshotId,
        spec.model(),
        spec.version(),
        spec.dim(),
        spec.preprocessingHash(),
        manifestHash,
        sourceRevision,
        sourceDirty,
        Timestamp.from(now));
  }

  public void addSourceFiles(String snapshotId, List<SourceFile> files) {
    jdbc.batchUpdate(
        "INSERT INTO index_source_file (snapshot_id, path, sha256, bytes) VALUES (?, ?, ?, ?)",
        files,
        200,
        (ps, f) -> {
          ps.setString(1, snapshotId);
          ps.setString(2, f.path());
          ps.setString(3, f.sha256());
          ps.setLong(4, f.bytes());
        });
  }

  public void addChunks(String snapshotId, List<ChunkRow> chunks) {
    jdbc.batchUpdate(
        """
        INSERT INTO doc_chunk (snapshot_id, chunk_id, document_id, path, section_path, domain_area, authority,
          lifecycle_status, superseded_by, proposed_superseded_by, precedence_rank, impl_record_date, impl_note,
          document_version, effective_date, stage, access_scope, content, content_hash, embedding)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector)
        ON CONFLICT (snapshot_id, chunk_id) DO NOTHING
        """,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            ChunkRow c = chunks.get(i);
            ps.setString(1, snapshotId);
            ps.setString(2, c.chunkId());
            ps.setString(3, c.documentId());
            ps.setString(4, c.path());
            ps.setString(5, c.sectionPath());
            ps.setString(6, c.domainArea());
            ps.setString(7, c.authority());
            ps.setString(8, c.lifecycleStatus());
            ps.setString(9, c.supersededBy());
            ps.setArray(
                10, ps.getConnection().createArrayOf("text", c.proposedSupersededBy().toArray()));
            ps.setInt(11, c.precedenceRank());
            ps.setString(12, c.implRecordDate());
            ps.setString(13, c.implNote());
            ps.setString(14, c.documentVersion());
            ps.setString(15, c.effectiveDate());
            ps.setString(16, c.stage());
            ps.setString(17, c.accessScope());
            ps.setString(18, c.content());
            ps.setString(19, c.contentHash());
            ps.setString(20, Vectors.literal(c.embedding()));
          }

          @Override
          public int getBatchSize() {
            return chunks.size();
          }
        });
  }

  /** BUILDING 인 스냅숏만 FAILED 로. 기존 ACTIVE 는 그대로다. */
  public void fail(String snapshotId, String reason) {
    jdbc.update(
        "UPDATE index_snapshot SET state = 'FAILED', failure_reason = ? WHERE snapshot_id = ? AND state = 'BUILDING'",
        reason.length() > 500 ? reason.substring(0, 500) : reason,
        snapshotId);
  }

  /**
   * 앞선 색인이 비정상 종료(프로세스 중단)로 남긴 BUILDING 을 모두 FAILED 로 — 그 뒤로는 {@link #prune} 이 유예 뒤 지운다. INGEST 잠금을
   * 쥔 채로만 부른다: 잠금이 세션 잠금이라 쥐고 있으면 다른 색인이 돌고 있지 않으므로 남은 BUILDING 은 모두 버려진 것이다.
   *
   * @return FAILED 로 바꾼 개수
   */
  public int failAbandonedBuilding(String reason) {
    return jdbc.update(
        "UPDATE index_snapshot SET state = 'FAILED', failure_reason = ? WHERE state = 'BUILDING'",
        reason);
  }

  /** 한 TX(스냅숏 전환 잠금 배타)에서 이전 ACTIVE → RETIRED, 이 스냅숏 BUILDING → ACTIVE. 이전 것은 지우지 않는다. */
  public void activate(String snapshotId, int chunkCount, Instant now) {
    tx.executeWithoutResult(
        s -> {
          lockSnapshotExclusive();
          jdbc.update(
              "UPDATE index_snapshot SET state = 'RETIRED', retired_at = ? WHERE state = 'ACTIVE'",
              Timestamp.from(now));
          int n =
              jdbc.update(
                  """
                  UPDATE index_snapshot SET state = 'ACTIVE', activated_at = ?, chunk_count = ?
                  WHERE snapshot_id = ? AND state = 'BUILDING'
                  """,
                  Timestamp.from(now),
                  chunkCount,
                  snapshotId);
          if (n != 1) {
            throw new IllegalStateException("BUILDING 이 아닌 스냅숏은 활성화하지 않는다: " + snapshotId);
          }
        });
  }

  public Optional<SnapshotRow> find(String snapshotId) {
    return jdbc
        .query(
            "SELECT * FROM index_snapshot WHERE snapshot_id = ?",
            SnapshotStore::snapshot,
            snapshotId)
        .stream()
        .findFirst();
  }

  public Optional<SnapshotRow> findActive() {
    return jdbc
        .query("SELECT * FROM index_snapshot WHERE state = 'ACTIVE'", SnapshotStore::snapshot)
        .stream()
        .findFirst();
  }

  public List<SourceFile> sourceFiles(String snapshotId) {
    return jdbc.query(
        "SELECT path, sha256, bytes FROM index_source_file WHERE snapshot_id = ? ORDER BY path",
        (rs, i) -> new SourceFile(rs.getString(1), rs.getString(2), rs.getLong(3)),
        snapshotId);
  }

  /**
   * RETIRED·FAILED 스냅숏 정리(수동 작업). 유예 시간이 지났고 그 스냅숏을 고정한 PROCESSING 요청이 없을 때만 지운다. 스냅숏 고정(공유 잠금)과 같은
   * 키의 배타 잠금 안에서 검사·삭제하므로, 고정 직전에 지워지는 경쟁이 없다.
   */
  public int prune(Instant olderThan) {
    Integer n =
        tx.execute(
            s -> {
              lockSnapshotExclusive();
              return jdbc.update(
                  """
                  DELETE FROM index_snapshot s
                  WHERE s.state IN ('RETIRED', 'FAILED')
                    AND COALESCE(s.retired_at, s.created_at) < ?
                    AND NOT EXISTS (SELECT 1 FROM policy_answer p
                                    WHERE p.snapshot_id = s.snapshot_id AND p.state = 'PROCESSING')
                  """,
                  Timestamp.from(olderThan));
            });
    return n == null ? 0 : n;
  }

  // ---- 임베딩 캐시: (모델, 버전, 차원, 전처리 해시, 본문 해시) 가 모두 같을 때만 ----

  public Optional<float[]> cachedEmbedding(EmbeddingSpec spec, String contentHash) {
    return jdbc
        .query(
            """
            SELECT embedding::text FROM embedding_cache
            WHERE model = ? AND model_version = ? AND dim = ? AND preprocessing_hash = ? AND content_hash = ?
            """,
            (rs, i) -> Vectors.parse(rs.getString(1)),
            spec.model(),
            spec.version(),
            spec.dim(),
            spec.preprocessingHash(),
            contentHash)
        .stream()
        .findFirst();
  }

  public void cacheEmbedding(EmbeddingSpec spec, String contentHash, float[] embedding) {
    jdbc.update(
        """
        INSERT INTO embedding_cache (model, model_version, dim, preprocessing_hash, content_hash, embedding)
        VALUES (?, ?, ?, ?, ?, ?::vector) ON CONFLICT DO NOTHING
        """,
        spec.model(),
        spec.version(),
        spec.dim(),
        spec.preprocessingHash(),
        contentHash,
        Vectors.literal(embedding));
  }

  private static SnapshotRow snapshot(java.sql.ResultSet rs, int i) throws SQLException {
    return new SnapshotRow(
        rs.getString("snapshot_id"),
        rs.getString("embedding_model"),
        rs.getString("embedding_model_version"),
        rs.getInt("embedding_dim"),
        rs.getString("preprocessing_hash"),
        rs.getString("manifest_hash"),
        rs.getString("source_revision"),
        rs.getBoolean("source_dirty"),
        rs.getString("state"),
        rs.getInt("chunk_count"),
        rs.getString("failure_reason"),
        rs.getTimestamp("created_at").toInstant(),
        instant(rs.getTimestamp("activated_at")),
        instant(rs.getTimestamp("retired_at")));
  }

  /** 스냅숏 전환 잠금(배타, 트랜잭션 끝에 풀림). 활성 전환·삭제. */
  void lockSnapshotExclusive() {
    jdbc.queryForObject(
        "SELECT 1 FROM (SELECT pg_advisory_xact_lock(?)) l", Integer.class, AdvisoryLocks.SNAPSHOT);
  }

  static @Nullable Instant instant(@Nullable Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
